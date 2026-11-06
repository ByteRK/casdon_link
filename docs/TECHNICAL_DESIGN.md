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
| `storage` | 本地设备记录与用户选择的修复包元数据；不保存操作历史 |

界面依赖 repository/use-case 接口，不直接操作 Socket、文件协议或 ZIP。设备记录采用本地持久化；密码功能当前不需要。所有网络 IO 放在后台协程/执行器，并由统一连接会话串行调度，避免并发命令相互混淆。Activity 生命周期变化时明确管理连接提示及关闭策略。

## 3. Telnet 会话设计

- 使用 TCP socket 连接目标 IP 与端口（默认 23），提供连接中、已连接、断开、超时和错误等状态。无需登录表单；设备环境若出现认证提示，应明确报错，不猜测凭证。
- 实现 Telnet IAC 协商处理和 NVT 文本通道，不能把 socket 当作透明原始串口。建立后发送 CRLF 唤起 shell 提示符，再进入命令交互。
- 终端需要交互式输入输出，支持基本回显、换行、退格及 ANSI 控制序列的最小处理。终端并发运行命令与状态/文件请求时应通过单一会话队列避免响应串流。
- Shell 命令的结束不能只依赖换行或固定延时；封装受控命令并追加唯一结束标记，再检测标记和提示符。超时或输出量过大时中止当前操作并恢复会话状态。对交互式/持续运行命令，保留原始终端流模式。
- Telnet 无加密、无认证，提供明确的连接目标确认和可信网络提示；不得把连接记录描述为安全凭证。

## 4. 设备状态与文件管理

### 状态采集

优先组合设备常见接口读取信息，如 `uname`、`hostname`、`/proc/uptime`、`/proc/meminfo`、`/proc/stat`、`df`、`ip` 或 BusyBox `ifconfig`。命令需检测可用性并允许字段缺失；解析失败显示“不可用”，不令整页失败。CPU 使用率可在两次 `/proc/stat` 采样间隔后计算；存储值来自 `df`，并标明挂载路径。

### 目录与文件操作

通过 shell 命令列出路径、读取文本和执行文件管理。对路径参数进行可靠的 shell 转义；禁止拼接未经校验的用户输入。列目录时采用可解析的结构化输出，并区分文件、目录、大小和权限。二进制文件不可直接作为 Telnet 文本回显；下载使用编码分块或 HTTP 文件接收机制，上传使用 HTTP `wget` 首选。重命名/删除前提供目标确认，写入失败呈现设备原始错误摘要。

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

设备记录字段：本地 ID、显示名称、IP/主机名、端口、最近连接时间（可选）。默认端口 23。仅保存用户需要的连接记录，不保存 shell 命令历史、文件操作日志或修复执行历史。修复包由系统文件选择器导入；只缓存执行所需的包文件及元数据，允许用户移除。

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

