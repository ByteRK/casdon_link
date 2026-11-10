# 技术方案

## 1. 目标与已知设备环境

Android App 在与嵌入式 Linux 设备同一局域网时，通过内置 Telnet 客户端进行售后查看和修复。设备实测环境：BusyBox 1.36.1、Buildroot、Linux 6.1.118、ARMv7；Telnet 服务为 `telnetd -l /bin/sh`，端口 23，直接进入 root shell，无账号密码。服务端不主动发送 banner，客户端需发送一次 CRLF 才能触发提示符。

设备不支持 ZMODEM/XMODEM/YMODEM；Telnet NVT 会改变 `CR LF` 和 `CR NUL` 序列，不能把任意二进制直接写入交互流。根 squashfs 只读且已满；`/userdata`（约 150 MB）和 `/tmp`（tmpfs，约 112 MB）实测可写。设备有 `wget`、`tftp`、`base64`、`uuencode/uudecode` 与 `tar`。HTTP `wget` 下载已实测可用。

## 2. Android 技术选型与结构

当前工程为 Kotlin Android App，最低 SDK 24。保持界面可在 Android Studio 预览和编辑：页面结构优先使用 XML layout、drawable、color、style 等资源；Kotlin 负责状态绑定、交互和业务逻辑，不以代码动态拼装整页布局。统一使用深色主题、圆角卡片、细边框和蓝绿强调色；保持文案短、留白足，动效控制在约 200 ms。

按职责拆分，避免 Activity 承担连接、协议和业务细节：

| 层/模块 | 职责 |
| --- | --- |
| `ui` | Activity/Fragment、XML 页面、列表适配器、导航与轻量展示状态 |
| `connection` | Telnet 连接生命周期、连接状态、读写流和重连/关闭 |
| `protocol` | Telnet 协商及 NVT 编解码；命令请求、提示符检测和输出分帧 |
| `device` | 状态采集命令、输出解析、设备信息模型 |
| `files` | 目录浏览、文本查看、文件操作及传输策略 |
| `package` | ZIP 导入、清单校验、路径安全校验、步骤计划与执行状态 |
| `profile` | 机型规则加载、只读文件探测、优先级匹配、授权文件模板与快捷动作模型 |
| `storage` | 本地设备记录、修复包元数据与授权操作记录；不保存 shell 命令历史 |

界面依赖 repository/use-case 接口，不直接操作 Socket、文件协议或 ZIP。设备记录采用本地持久化；密码功能当前不需要。所有网络 IO 放在后台协程/执行器，并由统一连接会话串行调度，避免并发命令相互混淆。Activity 生命周期变化时明确管理连接提示及关闭策略。

## 3. Telnet 会话设计

- 使用 TCP socket 连接目标 IP 与端口（默认 23），提供连接中、已连接、断开、超时和错误等状态。形如 `login:` / `Password:` 的认证提示会在建立连接时自动应答：使用用户填写的凭证完成登录；设备无需登录时，用户名与密码留空即可。不猜测也不内置任何默认凭证。
- 凭证仅用于当次连接：密码不写入设备记录、不落盘；日志中的密码及其回显统一脱敏。缺密码或凭证错误时向用户明确报错，便于补全或更正。
- 实现 Telnet IAC 协商处理和 NVT 文本通道，不能把 socket 当作透明原始串口。建立后发送 CRLF 唤起 shell 提示符，再进入命令交互。
- 终端需要交互式输入输出，支持基本回显、换行、退格及 ANSI 控制序列的最小处理。会话内只允许一个后台读取者（收集器）消费 socket；终端输出、状态查询、文件与修复包命令都基于该单一数据流，避免多个协程抢读导致响应串流。受控命令（带结束标记）执行期间不向终端转发，内部标记不污染终端视图。
- Shell 命令的结束不能只依赖换行或固定延时；封装受控命令并追加唯一结束标记，再检测标记和提示符。交互式 shell 会回显整行命令，因此起止标记在命令中必须拆成多段由设备端拼接，避免回显中的完整标记被误判为命令结束；命令正文取「起始标记之后、结束标记之前」，与设备是否回显无关。超时或输出量过大时中止当前操作并恢复会话状态。对交互式/持续运行命令，保留原始终端流模式。
- Telnet 无加密、无认证，提供明确的连接目标确认和可信网络提示；不得把连接记录描述为安全凭证。

## 4. 设备状态与文件管理

### 状态采集

优先组合设备常见接口读取信息，如 `uname`、`hostname`、`/proc/uptime`、`/proc/meminfo`、`/proc/stat`、`df`、`ip` 或 BusyBox `ifconfig`。命令需检测可用性并允许字段缺失；解析失败显示“不可用”，不令整页失败。CPU 使用率可在两次 `/proc/stat` 采样间隔后计算；存储值来自 `df`，并标明挂载路径。

### 目录与文件操作

通过 shell 命令列出路径、读取文本和执行文件管理。对路径参数进行可靠的 shell 转义；禁止拼接未经校验的用户输入。列目录时采用可解析的结构化输出，并区分文件、目录、大小和权限；结构化命令应避免 `!` 等会触发交互式 shell 历史展开的字符，设备不配合时退回 `ls -1ap`（以结尾 `/` 判断目录），保证至少能浏览。文件大小只读取元数据（`stat -c %s`，仅在不可用时对普通文件退回 `wc`），不得通过读取文件内容获取大小：设备节点和管道会阻塞，大文件也会拖慢整个目录。二进制文件不可直接作为 Telnet 文本回显；下载使用编码分块或 HTTP 文件接收机制，上传使用 HTTP `wget` 首选。重命名/删除前提供目标确认，写入失败呈现设备原始错误摘要。

设备 pty 可能开启 `tab3`（OXTABS），把输出里的 TAB 展开成按 8 列对齐的空格，使制表符分隔的结构化输出无法解析；会话建立后先尽力执行 `stty tab0` 关闭该行为，解析器同时保留「类型 + 空白 + 大小 + 空白 + 名称」的兼容分支，两种情况下都能得到文件列表。同理，交互式 shell 会在超过屏幕宽度处插入用于重绘的孤立 CR，构造受控命令时不能把孤立 CR 当作换行，否则一条命令或一行文件列表会被劈成两半；命令回显本身（含孤立 CR）不进入结果正文，也不写入诊断日志。

设备本地 HTTP server 通常不能由 App 假定已安装。文件传输首选由 App 在手机上启动临时、仅绑定可用局域网接口的 HTTP server，设备执行 `wget` 从手机拉取文件。若 Android 网络隔离、热点/路由策略或防火墙使设备无法访问手机，则提示失败原因并提供备选流程：TFTP 客户端（设备端 `tftp -g`）或通过 base64/uuencode 安全文本分块传输。传输目标必须是设备可写位置；写入根目录应拒绝或警告。

下载方向可在设备端生成编码文本，再由 App 解码；也可在设备端主动 HTTP 上传，但前提是实现 App 的临时接收端且设备工具支持相应上传方式。大文件需分块、校验长度及 SHA-256；由于 Telnet 文本流存在 NVT 转换，不允许未经编码的任意二进制流。

## 5. 修复包规范（ZIP）

ZIP 根目录必须包含 `manifest.json`，其余资源使用相对路径。资源路径只能位于 ZIP 内，拒绝绝对路径、`..` 路径穿越、重复路径、符号链接和解压炸弹（限制总解压大小、单文件大小、条目数与压缩比）。校验完整通过后才允许执行；导入不执行任何内容。

示例：

```json
{
  "formatVersion": 1,
  "packageId": "fix-camera-timeout",
  "name": "Camera timeout fix",
  "version": "1.0.0",
  "description": "修复摄像头服务超时",
  "steps": [
    {
      "id": "backup-config",
      "type": "shell",
      "name": "备份配置",
      "command": "cp /userdata/camera.conf /userdata/camera.conf.bak"
    },
    {
      "id": "upload-config",
      "type": "file",
      "name": "更新配置",
      "source": "files/camera.conf",
      "target": "/userdata/camera.conf",
      "sha256": "<64位十六进制摘要>"
    },
    {
      "id": "restart-service",
      "type": "shell",
      "name": "重启服务",
      "command": "killall camera-service; /usr/bin/camera-service"
    }
  ]
}
```

规范约定：

- `formatVersion` 为必填整数；未知版本拒绝执行。`packageId`、`name`、`version`、`steps` 必填，步骤 `id` 唯一。
- 步骤数组就是执行顺序；每步完成并报告状态后才进入下一步。`file` 步骤传输一个资源到明确 `target`，`shell` 步骤由 App 逐条发送 `command`，可选 `timeoutSeconds` 和 `continueOnError`（默认 false）。
- App 不运行 ZIP 内的脚本文件作为本地代码。设备侧脚本若需执行，先用 `file` 步骤传到设备可写路径，再由后续 `shell` 步骤显式调用，例如 `sh /userdata/fix.sh`。这样文件上传与执行次序仍由清单控制。
- 清单中的 Shell 命令具有设备 root 权限，导入后展示包名、步骤数、目标路径和命令摘要，执行前要求用户明确确认。执行过程允许取消；取消时停止后续步骤并报告当前步骤结果。每步显示进行中/成功/失败/跳过，但默认不长期保留操作日志。
- 所有目标路径经规范化和策略校验；拒绝路径穿越、空路径和不允许的覆盖策略。目标路径可由包作者指定，但界面需展示。可选校验 `sha256` 验证包内源文件及传输后的设备文件（若设备端 `sha256sum` 可用）。

## 6. 本地数据与隐私

设备记录字段：本地 ID、显示名称、IP/主机名、端口、登录用户名、登录密码、最近连接时间（可选）。默认端口 23。仅保存用户需要的连接记录，不保存 shell 命令历史、文件操作日志或修复执行历史。修复包由系统文件选择器导入；只缓存执行所需的包文件及元数据，允许用户移除。

登录密码属于需要记忆的凭证：明文只在内存中出现，持久化前用 Android Keystore 托管的 AES/GCM 密钥加密，SharedPreferences 中仅存 Base64 密文，密钥不可导出。密钥失效（恢复出厂、备份恢复到新设备）时按“未保存密码”处理，由用户重新输入，不影响记录本身。设备记录因此不进入云备份与设备迁移。输入框留空时复用该地址已保存的凭证，手动输入的值优先；连接成功后保存实际生效的凭证，长按记录可清除已保存密码或删除整条记录。密码不写入诊断日志与终端流。

## 7. 权限与失败处理

- 声明 `INTERNET` 与网络状态相关权限；按 Android 版本与实际实现配置明文局域网 socket 策略。Android 9+ 默认限制明文流量，Telnet 必须由应用网络安全配置明确允许；仅限本项目设备连接场景。
- Android 新版本的本地网络访问策略、热点/多网卡路由可能影响手机临时 HTTP server。连接前探测可用接口，失败时给出网络设置提示；不假设手机和设备仅凭“同一 Wi-Fi”一定可互访。
- 所有连接、命令、解析和传输错误以短提示呈现，可展开查看技术详情；默认不持久化日志。
- 对目标设备命令能力差异做运行时探测，BusyBox applet 缺失时隐藏或禁用相关操作，而不是假设完整 GNU/Linux。

## 8. 建议交付顺序

1. XML 首页、深色主题、设备记录与 TCP/Telnet 连接状态。
2. Telnet NVT 会话与交互终端。
3. 设备信息采集与解析。
4. 目录浏览、文本预览与文件操作。
5. ZIP 清单校验、修复步骤预览和逐步执行。
6. 手机临时 HTTP 下载服务、备用传输策略、校验与异常恢复。

## 9. 当前实现说明

目前工程已包含 XML 连接首页、Telnet 终端、文件浏览、修复包导入与有序执行。清单解析器支持 `shell` 与 `file` 步骤，并检查格式版本、重复步骤 ID、ZIP 路径穿越、条目/总体解压大小和 SHA-256。当前文件浏览上传实现由设备 `wget` 拉取手机临时 HTTP 文件，并保留系统文件选择器提供的原文件名；下载实现设备端分块 `dd | base64` 输出后在手机解码并按块校验长度，避免整文件输出挤占受控命令匹配缓冲。受 NVT 约束，尚未实现原始二进制 Telnet 上传。

上传链路的抗抖动策略（现场无线链路会出现秒级延迟抖动，实测返回包 RTT 可从 30 ms 升到 500 ms 以上）：

- 手机上的一次性 HTTP 服务端持续 accept 直到关闭（单次上传可能断开重来），支持 `HEAD` 与 `Range: bytes=<已下载>-`（返回 `206 Partial Content` 与 `Content-Range`），并开启 `TCP_NODELAY`。
- 设备端 `wget` 使用 `-c`：已有半成品时按 Range 续传；若服务端不支持 Range（回 200 整文件），BusyBox wget 会从头重写而不是叠加。
- 进度轮询失败不等于传输失败：轮询命令超时或输出不可解析时只记「本轮没有样本」，只有设备端文件大小在 90 秒内没有增长才判定中断；轮询自身超时上限 15 秒，间隔 1 秒。
- 中断后保留 `.casdonpart` 并重试（最多 3 次）；替换目标前先校验期望大小与清单里的 SHA-256，校验不通过则删除半成品整份重下，绝不覆盖设备上的原文件。
- 同一次修包执行复用同一个仓库实例，目录可写探测按目录缓存；执行期间保持屏幕常亮，步骤行第二行显示「已传 / 总量 · 百分比 · 速率」，便于区分「慢」与「卡死」。

修复包步骤执行要求当前已有 Telnet 会话；每个步骤完成后更新页面状态。界面只展示步骤标题、状态圆点（待执行 / 执行中 / 成功 / 失败）与文件步骤的传输进度，不展示 shell 命令、传输路径等步骤细节；执行前仅做一次二次确认弹窗，不列出具体步骤内容。失败时除了圆点变红，还会用 toast 补上失败原因。清单脚本作为设备命令运行，因此必须将修复包视为有 root 权限的可信内容。

完整功能开关：连接成功后设备页默认只显示「修复包」，交互终端、文件管理等高级操作以及后续新增的受限功能都放在 `advancedActions` 容器中并默认隐藏。只有在首页左上角标题区连续点击 6 下（相邻点击间隔不超过 1.2 秒）并输入正确密码才开启完整功能；开关持久化在本机 SharedPreferences，应用与手机重启后仍有效。已开启时再次连续点击 6 下即可关闭。密码常量位于 `data/FeatureGate.kt` 的 `UNLOCK_PASSWORD`，更换密码只改这一处。

开启密码弹窗禁止点击外部关闭（`setCanceledOnTouchOutside(false)`），只能按「取消」或返回键退出；否则连点手势多出来的那一下会落在弹窗外，把刚弹出的窗口立刻取消，看起来像屏幕闪黑。手势触发后有 800ms 冷却，弹窗显示期间也忽略标题区点击，避免同一串连点重复触发。软键盘状态在 `show()` 前设定，减少窗口重绘。

会话数据流采用单一后台收集器：握手完成后由唯一协程读取 socket，解码结果同时供终端订阅与受控命令的结束标记匹配；终端未在前台时输出进入有上限的回放缓冲，回到终端页即补发。设备断开由收集器检测 EOF 统一通知，终端页与设备页会同步退出并回到首页。

交互终端：握手阶段读到的 shell 提示符（如 `sh-5.2# `）会补进终端流，终端页首次打开即可看到前缀而不是空白——这是唯一一次"补显示"，之后完全按设备原生回显显示，不在客户端做提示符/命令回显的匹配与吞并（设备顺序实测为「命令回显 → 输出 → 提示符」，分块边界不稳定，客户端猜顺序会误吞真实输出）。提示符只取匹配到的最后一行并原样显示，不补造内容：设备提示符带不带路径由设备决定，应用不跟踪 `pwd`（在终端页临时执行 `pwd` 会与用户输入争抢同一个 shell 与匹配缓冲，需要时应在握手阶段、收集协程启动前查询）。发送前会去掉命令首尾空白，避免多出的前导空格在回显行里变成双空格。输出区滚到底部只调整 `scrollY`，不使用 `ScrollView.fullScroll(FOCUS_DOWN)`（它会向可聚焦的输出 `TextView` 请求焦点，把输入框的焦点抢走并收起键盘）。发送命令后还会主动把焦点交还输入框，保证连续输入不中断。

终端输入框使用普通文本输入（`textNoSuggestions`），不锁键盘类型、也不强制 ASCII：设备端 shell 能处理中文路径与参数，由用户按需切换输入法。此前的 `textVisiblePassword` + `flagForceAscii` 会强制英文键盘，虽然规避了拼音输入法把字母吞进组合的问题，但代价是无法输入中文，已去掉。

远程调试备注：若只能用 `adb shell input text` 注入命令，拼音输入法可能把字母吞进组合导致注入不进去，此时可临时把 `commandInput` 改回 `textVisiblePassword` + `flagForceAscii`（即"明文密码键盘"，各输入法都会直接上英文键盘且文本仍可见），调完必须改回 `textNoSuggestions` 并确认不随改动提交。

输入框上方另有一排可横向滑动的控制键：`Esc`、`Tab`、`Ctrl+C`、`Ctrl+D`、`Ctrl+Z`、`Ctrl+L` 直接把对应控制字符写入会话（中断、补全、清屏等软键盘打不出的操作），发送内容在诊断日志里统一记成 `\xNN`；`↑`/`↓` 在本地命令历史中回放（不依赖设备端 readline 状态，避免与本地输入框内容错位）；`|` `/` `-` `~` 插入光标处，由用户补完命令后统一发送。这些按钮在样式里设 `focusable=false`，点击不会抢走输入框焦点，软键盘保持展开。

控制键背景单独用 `bg_key`（10dp 圆角），不复用输入框的 `bg_input`（14dp）：按键只有 40dp 高，14dp 圆角会把上下弧线压得几乎占满高度，底部看起来像被平切掉一截。实测改为 10dp 后上下圆角轮廓基本对称（偏差在 1dp 以内）。

## 10. 界面样式约定

界面视觉参数集中在 `res/values/colors.xml`、`res/values/dimens.xml`、`res/values/styles.xml` 三处，布局文件只引用样式和令牌，不写裸字号、颜色、圆角与内边距。

系统栏：页面创建时必须调用 `ui/SystemBars.kt` 的 `applyDarkSystemBars()`，不要直接调用 `enableEdgeToEdge()`。后者默认的 `SystemBarStyle.auto` 按**系统**深色/浅色模式决定图标深浅，在系统浅色模式下会给深色图标，落在 `@color/background` 上就看不见；本应用背景始终为深色，因此固定用 `SystemBarStyle.dark`（透明底 + 浅色图标），与主题里的 `windowLightStatusBar`/`windowLightNavigationBar` = false 一致。

- `colors.xml`：只放语义色（`background`、`surface`、`primary`、`secondary`、`text_primary`、`text_secondary`、`text_hint`、`danger`、`divider`、`input_surface`、`input_stroke`、`card_stroke`）。新增颜色先判断能否复用已有语义色。
- `dimens.xml`：`space_*` 为 4dp 栅格间距；`screen_padding_*`、`card_padding`、`row_padding` 控制留白；`control_height*`、`toolbar_height`、`list_row_height` 控制控件尺寸；`text_*` 为字号。禁止在布局中重复写同类数值。
- `styles.xml`：`Text.*` 只描述文字（字号、颜色、字重、行距），`Widget.*` 描述完整控件（尺寸、背景、内边距、文字）。常用项：
  - 文字：`Text.CasdonLink.Eyebrow`（顶部小标题）、`HeroTitle`/`ScreenTitle`/`ToolbarTitle`/`SectionTitle`（层级标题）、`Subtitle`/`Label`/`Caption`/`MultilineCaption`、`Body`/`MultilineBody`、`Status`、`Mono`、`ListItemTitle`/`ListItemStrongTitle`/`ListItemMeta`（含 `.Danger` 失败色变体）、`DialogTitle`。
  - 超长标题：`ToolbarTitle` 与 `ListItemStrongTitle` 已内置单行跑马灯（`singleLine` + `ellipsize=marquee` + `marqueeRepeatLimit=marquee_forever` + `selected=true`），文本未超出宽度时不会滚动；使用时父布局必须给出宽度约束（`0dp` + `layout_weight`，或 `match_parent`）。RecyclerView 行内复用需在绑定后再设一次 `isSelected`，否则文本换新后跑马灯不会启动。
  - 容器：`Screen.Scroll`/`Screen.ScrollContent`/`Screen.Column`、`Card`、`Toolbar`、`List`、`ListRow`/`ListRowBlock`、`RecentDeviceRow`。
  - 按钮：`Button.Primary`（主操作）、`Button.Secondary`（次操作）、`Button.Small`（小号次操作）、`Button.Danger`（危险操作）、`Button.Icon`（返回符号）、`Button.Inline`（行内固定宽度）。
  - 输入：`EditText`、`EditText.Port`。
- 启动图标：全部为矢量，项目已不含位图图标。
  - 背景：`ic_launcher_background` 用设计稿原色 `@color/ic_launcher_background`（`#3F70FF`），不跟随应用深色背景。
  - 前景：符号来自设计稿 `API.svg`，1024 画布内实测 640×640、居中（占 62.5%）。自适应图标只显示中间 66.7%，直接铺满会让符号相对可见区放大到约 94%，因此 `ic_launcher_foreground_inset` 用 `inset="16.67%"` 内缩，使符号在可见区中仍占约 62.5%，与设计稿一致。
  - `mipmap-anydpi-v26/ic_launcher{,_round}.xml` 为 adaptive-icon（background + foreground + monochrome，monochrome 与前景区用同一份资源）；API 24–25 回退到 `mipmap-anydpi/ic_launcher{,_round}.xml`（`layer-list` + `ic_launcher_legacy_background{,_round}` 圆角/圆形底色，前景用未内缩的 `ic_launcher_foreground`，因为 layer-list 不受 66.7% 裁切限制）。
  - 弹窗：统一用 `MaterialAlertDialogBuilder`，主题为 `ThemeOverlay.CasdonLink.MaterialAlertDialog`（深色表面、20dp 圆角、语义文字色）；输入类弹窗内容用 `dialog_input.xml`，长文本用 `dialog_text.xml`。
  - 悬浮菜单：主题为 `ThemeOverlay.CasdonLink.PopupMenu`，控件样式为 `Widget.CasdonLink.PopupMenu`，背景 `bg_popup_menu` 与卡片同色同描边。

新建页面的做法：根布局用 `Widget.CasdonLink.Screen.*`，标题栏用 `Widget.CasdonLink.Toolbar`，卡片用 `Card`，按钮只从上述按钮样式里选。需要局部差异时在布局中写少量覆盖属性，跨页面复用的差异则新增子样式（例如 `Button.Secondary.Wide`），不要在多个布局里重复同一组属性。列表行统一使用独立 `row_*.xml`，代码只负责绑定数据和事件，不得在 Kotlin 中拼装样式。弹窗与菜单也必须走上述主题，不要直接使用 `android.app.AlertDialog` 或默认 `PopupMenu`，否则会跟随系统浅色主题而与页面割裂。

列表控件统一使用 `RecyclerView`：

- 整页列表（文件页、修复步骤页）在布局里用 `style="@style/Widget.CasdonLink.List"`，代码里调用 `ui/ListSetup.kt` 的 `configureAsList()` 完成纵向布局与 1dp 分隔线（`divider_list`）；RecyclerView 不读 `android:divider`，分隔线必须由 `DividerItemDecoration` 提供。
- 适配器统一用 `ListAdapter` + `DiffUtil`，行视图放独立 `row_*.xml`，Holder 只绑数据并把点击/长按转发给 Activity 回调；行按下反馈由 `Widget.CasdonLink.ListRow` 的 `selectableItemBackground` 提供。
- 嵌在其他可滚动页面里的列表（首页「最近设备」）外层用 `androidx.core.widget.NestedScrollView`，条目超过 3 行时把列表高度固定为 `RECENT_VISIBLE_ROWS × (recent_device_row_height + space_md)` 并在列表内滚动，未超过时用 `wrap_content`，避免记录累积把首页撑长；无记录时整块（标题 + 列表）隐藏。

## 11. 版本号规范

版本号采用 `MAJOR.MINOR.PATCH` 三段式，**每一次改动都必须递增其中一位**，不允许出现「代码变了但版本号没变」的提交。

递增规则（取本次改动中最高的那一类）：

| 段 | 触发条件 | 示例 |
| --- | --- | --- |
| `MAJOR` | 不兼容变更：修复包清单格式不兼容、已保存数据的结构/加密方式变化、需要用户重新配置或迁移、整体重写 | `1.x.x` → `2.0.0` |
| `MINOR` | 新增功能或新交互，且对已有数据和用法保持兼容：新增页面、新增能力、列表/弹窗行为变化 | `1.1.x` → `1.2.0` |
| `PATCH` | 行为不变的改动：缺陷修复、样式与文案调整、纯重构、文档与构建脚本调整 | `1.1.0` → `1.1.1` |

- 递增某一位时，右侧低位归零（`1.1.3` 升 MINOR → `1.2.0`）。
- 一次提交（或一批改动）只递增一次，按其中最高级别计；例如同时修 bug 和加功能，走 `MINOR`。
- 版本号只在 `app/build.gradle.kts` 顶部维护：`appMajor`/`appMinor`/`appPatch` 三个常量，`versionName` 与 `versionCode` 都由它们派生（`versionCode = MAJOR*10000 + MINOR*100 + PATCH`），不要直接写 `versionName` 字符串。这样首页页脚、APK 产物名（`CasdonLink_<buildType>_V<版本名>_<时间戳>.apk`）会同步更新。
- 首个三段式基线为 `1.1.0`：`1.0` 时代的两位版本号补齐为三段，并把在此之后累计的新功能（最近设备列表、首页页脚、系统栏修复等）计入 `MINOR`。

## 12. 机型规则、补发授权码与快捷操作（1.8.0）

### 12.1 目标与边界

设备页在连接后自动识别机型，命中规则时在 IP 旁显示机型名，并提供“补发授权码”和“快捷操作”。规则随 APK 内置在 `assets/profiles.json`，当前不开放外部导入；后续如需签名导入，可复用解析校验层并增加来源与版本字段。

识别只使用**只读文件检查**（`test -e/-f/-x`），规则里不写 shell 命令。这样即使规则文件泄露或被替换，也不会在连接阶段自动执行任意命令。

### 12.2 规则模型

一条规则包含：

- `id`、`name`、`priority`：唯一标识、显示名和冲突时的优先级。
- `match.all`/`match.none`：路径存在性条件；`all` 必须非空，避免规则变成“匹配所有设备”。
- `license`：`pid`、`networkInterface`、`targetPath`、`contentTemplate`（必须含 `{{pid}}`/`{{did}}`/`{{license}}`）、`read` 正则（PID/DID/License 至少各一个捕获组）。
- 字段值允许用 `<![CDATA[...]]>` 包裹（如 X Mee 的 `init` 段）；读取时会先去掉 CDATA 包裹层，再套用 `read` 正则，因此同一套简易正则可同时匹配带与不带 CDATA 的授权文件。
- `quickActions`：分组动作，每条动作可含一串命令，支持 `confirm`/`danger`/`output`/`timeoutSeconds`/`continueOnError`/`reboot`。

加载时逐条校验，任何字段非法就跳过该规则并写诊断日志。多个规则同时命中时按 `priority` 选择，并在机型详情里提示冲突。

内置规则当前覆盖：

| 机型 | 应用二进制 | PID | 授权文件 |
| --- | --- | --- | --- |
| 旗舰洗碗机 Z200Muse | `/userdata/app/Z200Muse` | `VTrRZRWy` | `/userdata/devices.xml` |
| 旗舰烟机 YV100 | `/userdata/app/yv100` | `U7H9Vi6d` | `/userdata/devices.xml` |
| 旗舰烟机 YT100 | `/userdata/app/yt100` | `Otc9AWgB` | `/userdata/devices.xml` |
| 台式微蒸烤 B10 Pro | `/userdata/app/b10pro` | `vfIS2vgn` | `/userdata/devices.xml` |
| 旗舰微蒸烤 X Mee | `/userdata/app/bin/HighXMSG` | `HiD4WlfL` | `/userdata/devices.xml` |

### 12.3 识别

连接后把全部规则的路径检查合并成一次 `execute`，输出 `CLP<index>=1/0`，避免逐条命令往返。识别在后台协程执行，不阻塞终端、文件和修复包。结果为：

- 识别中；已匹配（显示规则名）；未知机型；识别失败（可重试）。
- 完整功能模式下可手动选择机型，徽标显示“手动 · 机型名”，授权确认仍需核对 PID。
- 徽标点击展示规则详情，包括 PID、网卡、授权路径、命中条件和冲突提示。

### 12.4 补发授权码

独立页面 `LicenseActivity` 负责完整流程：

1. 读取 `/sys/class/net/<网卡>/address` 和当前授权文件；文件不存在显示“未授权”。
2. 展示机型、PID、MAC、目标路径和当前 PID/DID；要求勾选“已核对设备铭牌与 PID 一致”。
3. 向 `MacToAuthCode` 的 `POST /api/device/authorize` 发送 `{pid, mac}`。服务端对同一 PID+MAC 幂等，`mode=reused` 表示复用原授权，失败 409 时展示服务端文案。
4. 用模板生成授权文件，`base64 -d` 写入目标路径；写命令走 `execute(secret = true)`，授权码不进入诊断日志。
5. `cat` 读回校验 PID/DID/License，`sync` 后发送 `sync; reboot`。
6. 重启前记录 `/proc/sys/kernel/random/boot_id`；重连后必须看到不同 boot_id，才认为设备真正重启完成。
7. 旧 socket 在 reboot 后可能仍保持打开但已不响应，`reconnectAfterReboot` 会主动关闭旧会话，只接受新握手，避免提前显示成功。
8. 若设备更换 IP，无法自动找回；等待超时后提示“可能已更换 IP，请重新连接”。
9. 设备重启并重新连接后，自动从设备重新读取授权文件并回填上方「当前授权」卡片（PID、DID、授权码；授权码仍只显示首尾）。成功结果不输出服务端返回的授权码：下方「操作」区只显示“设备已重新上线，已从设备读取当前授权”。若重连后读不到可解析的授权，卡片会明确提示，点击卡片可重新读取。

服务端地址以 XOR 字节数组存放并在运行时还原，避免明文出现在源码/资源中；这只能提高静态扫描门槛，不是安全边界。debug 构建可用 `-PauthBaseUrl=http://<host>:<port>` 指向本地平台，release 始终使用内置生产地址。

### 12.5 授权记录与导出

`LicenseHistoryStore` 在 `filesDir/license_records.json` 保存最近 200 条记录：时间、地址、机型、PID、MAC、DID、授权码、模式、目标路径、结果、错误信息、App 版本。授权码用 Android Keystore 加密后落盘；导出 JSON 时还原完整字段，导出文件按敏感文件管理。

记录在申请前创建，随后更新为“已分配/已写入/成功/失败”；应用中途被杀也能看到最后一次状态。授权页提供“导出授权记录”，通过系统文件选择器保存 `CasdonLink_license_records_<时间>.json`。

### 12.6 快捷操作

`QuickActionsActivity` 按规则分组展示动作，仅完整功能模式可见。执行规则：

- 全局串行、执行中禁用入口；危险动作二次确认。
- 命令队列逐条执行，默认失败停止；`continueOnError` 可继续。
- 输出超过规则阈值或用户要求时弹窗展示，可复制；页面底部显示状态与“停止”（当前命令结束后停止后续）。
- `reboot` 动作复用授权页的重启等待逻辑（boot_id 校验）。
- 内置规则统一提供「授权维护 / 清除授权码」动作：`rm -f <授权文件>` 后 `reboot`，危险操作需二次确认；`rm` 失败（如只读文件系统）时不再重启，便于定位问题。
- Z200Muse 另保留一组「调试操作」（`/userdata/test.txt` 增删查、`sh /tmp/test.sh`），用于现场验证，不属于正式维护流程。
- 快捷操作不替代交互终端：自由 shell 输入仍在终端页完成。

### 12.7 当前限制

- 规则只内置，新增机型需要随 APK 发版；签名导入尚未实现。
- 设备重启后若 DHCP 更换 IP，App 无法自动发现，只能提示用户重新连接。
- 授权记录中的完整授权码会随用户主动导出而离开本机，页面已提示按敏感文件保管。
- 快捷操作只执行规则内置命令队列，不支持运行时参数输入。

