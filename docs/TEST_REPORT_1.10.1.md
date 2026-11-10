# Casdon Link 1.10.1 云端变更分析与全量测试报告

测试时间：2026-11-11 06:15 ~ 06:30（Asia/Shanghai）
基线：`origin/master` `697c5e2`（1.10.1）
测试后修复版本：1.10.2（`CasdonLink_debug_V1.10.2_20261111-062307.apk`）
测试环境：Android 2602BRT18C / Android 17 / 10.0.0.70；实体机 RK3506B / Buildroot / 10.0.0.93（USB ADB `33b924ca24647e24`，带真实授权）

## 1. 拉取与变更范围

```text
2ff767a (本地 1.8.0 文档提交)
  ├─ 83b13f4 feat: add X Mee profile, CDATA license parsing and license maintenance actions (1.10.0)
  └─ 697c5e2 fix: make HTTP uploads resilient to Wi-Fi jitter with resume and verify (1.10.1)  <- origin/master
```

### 1.10.0（83b13f4）

- Z200Muse 名称改为“旗舰洗碗机 Z200Muse”；授权模板/读取从 `app_config/DeviceId/ProductId/License` 改为 `init/uuid/key/pid`。
- YV100/YT100/B10 Pro 的授权目标从 `/userdata/license.txt` 统一为 `/userdata/devices.xml`。
- 新增 X Mee 规则：`/userdata/app/bin/HighXMSG`、PID `HiD4WlfL`、目标 `/userdata/devices.xml`，模板使用 `<![CDATA[...]]>` 包裹值和额外 `link/sn` 字段。
- `LicenseText.parse` 读取前去掉 CDATA 包裹层；新增 CDATA 单元测试。
- 所有规则增加“授权维护 / 清除授权码”动作：`rm -f /userdata/devices.xml`、危险确认、动作结束后重启。
- `.gitignore` 增加 `app/release/`。

### 1.10.1（697c5e2）

- `PhoneHttpServer` 持续 accept 到关闭，支持 `HEAD` 与 `Range`（206/416），启用 `TCP_NODELAY` 与更大发送缓冲。
- 上传使用 `wget -c -O .casdonpart`，断链后保留半成品续传，最多 3 次；轮询超时（15 秒）只算“本轮无样本”，90 秒无增长才判定中断；间隔 1 秒。
- 替换目标前校验期望大小与清单 sha256；校验失败删除半成品并整份重下，绝不覆盖原文件。
- 修复包执行复用同一 `FileRepository`（目录可写探测缓存），执行期间保持屏幕常亮，步骤行新增传输进度。
- 新增进度行布局；`FilesActivity` 的 `uploadFromHttp` 改用 `onProgress` 参数。

## 2. 测试结果

| # | 用例 | 结果 | 说明 |
| --- | --- | --- | --- |
| 1 | 云端代码编译 | 通过 | `:app:assembleDebug` 成功 |
| 2 | 单元测试 | 通过 | 含新增 CDATA 解析测试 |
| 3 | 安装 / 版本 | 通过 | 1.10.1 → 测试后 1.10.2 |
| 4 | 连接 10.0.0.93 | 通过 | `连接正常 · Telnet 23` |
| 5 | 机型识别 / 徽标 | 通过 | 显示“旗舰洗碗机 Z200Muse” |
| 6 | 授权页读取存量授权 | 部分通过 | 云端口径为 `init/uuid/key/pid`，该机存量文件是 `app_config/DeviceId/ProductId/License`，无法解析；按用户确认“云端规则正确”，不做兼容。页面已改为安全提示，不再展示原文 |
| 7 | 授权服务自检 | 通过 | 点击“检测授权服务”显示“授权服务可访问”（生产地址） |
| 8 | 文件上传（Files 页） | 通过 | 38 B 文件上传到 `/userdata/upload_test.txt`，内容一致 |
| 9 | 修复包上传（1 MB + sha256 + shell） | 通过 | 目标 `/userdata/pkg_test.bin` 1,048,576 B，sha256 `a178…5cb74` 完全一致 |
| 10 | 续传 + 完整性校验（故障注入） | 通过 | 预置 512 KB 错误半成品；服务端收到 `Range 524288-1048575`；sha256 不一致；删除半成品后整份重下，最终哈希正确 |
| 11 | 快捷操作 touch/ls/rm/sh | 通过 | 创建、查看、危险确认删除、脚本输出均符合预期 |
| 12 | 快捷操作“停止” | 通过 | 长脚本执行中停止，显示“执行已停止”，未执行后续 |
| 13 | 清除授权码确认 | 通过（未执行） | 危险确认弹窗正确；为避免删除真实授权，取消执行 |
| 14 | 授权写入 / sync / reboot | 未执行 | 该机带真实授权，不在本轮覆盖；需用可牺牲设备复测 |
| 15 | X Mee / YV100 / YT100 / B10 Pro 真机 | 未覆盖 | 现场只有 Z200Muse 实体机；CDATA 解析有单元测试 |
| 16 | 未知机型 / 手动选机型 | 未覆盖 | 无第二台可连接设备 |
| 17 | 真实链路断开（非半成品损坏） | 未覆盖 | 已用错误半成品验证 Range 与校验分支，但未注入真实断网 |

## 3. 发现与修复

### 3.1 授权文件解析失败时泄露完整 License（已修复，1.10.2）

现象：存量授权文件格式与云端规则不一致时，`LicenseActivity` 的“当前授权”直接显示 `currentLicenseRaw.take(500)`，会把完整 License 显示在页面上。

修复：只显示“已存在授权文件，但无法按机型规则解析，请确认型号/PID/格式”，不再展示原始内容。

### 3.2 上传完成后的 `totalBytes=0` 时序问题（已修复，1.10.2）

`uploadFromHttp` 在 `mv part target` 之后才执行 `remoteSize(part)`（仅 `totalBytes == 0` 时），此时半成品已不存在，会抛异常并把成功上传误判为失败。当前调用方都传了非零总大小，所以本轮未触发；已把大小读取移到 `mv` 之前。

### 3.3 快捷操作输出带 ANSI 控制序列（已修复，1.10.2）

设备 `ls` 输出带 `ESC[0;0m` 等颜色序列，弹窗显示为乱码。结果展示与复制前统一去掉 ANSI 序列。

### 3.4 Z200Muse 存量授权格式与云端新规则不一致（按用户决定不兼容）

- 实测设备 10.0.0.93 的 `/userdata/devices.xml` 为 315 B、`app_config` 段、`DeviceId sn9wUu4BvzzB` / `ProductId VTrRZRWy`。
- 云端规则写入/读取 `init/uuid/key/pid`，因此该机授权页无法解析其存量授权；这是规则格式差异，不是解析崩溃。
- 用户确认云端规则正确，所以本报告不修改规则、不做双格式兼容；只修复了“解析失败时泄露原文”的 UI 问题。
- 风险：若现场仍存在 `app_config` 格式的存量设备，补发/查看前需要先确认格式或迁移。

### 3.5 其他观察（未修改）

- `PhoneHttpServer` 的 URL 仍直接用文件名拼接；文件名含空格/非 ASCII 时设备端 `wget` 可能取不到。当前修复包测试用 ASCII 文件名未触发。
- 取消上传时 `kill $pid` 杀的是后台子 shell，`wget` 子进程可能继续写半成品；未做长时间断网注入验证。
- sha256 命令超时会降级为只校验大小（代码注释已说明）；网络抖动很大时仍以大小为准。
- 1 MB 传输速度太快，UI 的“已传/总量·速率”行停留时间很短；进度逻辑已由代码和日志确认，未做 12 MB/29 步长跑。

## 4. 证据

- 设备识别与徽标：[ui-device-1.10.1.xml](test-evidence-1.10.1/ui-device-1.10.1.xml)
- 授权页安全提示：[ui-license-safe.xml](test-evidence-1.10.1/ui-license-safe.xml)
- 授权服务自检：[ui-service-check.xml](test-evidence-1.10.1/ui-service-check.xml)
- 修复包导入/确认：[ui-package-imported.xml](test-evidence-1.10.1/ui-package-imported.xml)、[ui-package-confirm.xml](test-evidence-1.10.1/ui-package-confirm.xml)
- 快捷操作停止：[ui-quick-stop.xml](test-evidence-1.10.1/ui-quick-stop.xml)
- 快捷操作输出（已去 ANSI）：[ui-quick-output-clean.xml](test-evidence-1.10.1/ui-quick-output-clean.xml)
- 清除授权码危险确认：[ui-clear-license-confirm.xml](test-evidence-1.10.1/ui-clear-license-confirm.xml)
- 续传/校验日志：[upload-resume-log.txt](test-evidence-1.10.1/upload-resume-log.txt)

## 5. 结论与建议

- 1.10.0 的规则结构、X Mee/CDATA、授权维护动作可正常加载；1.10.1 的 HTTP 续传与 sha256 校验在真实设备上通过，故障注入验证了 Range + 校验失败重下。
- 本轮修复了 3 个问题（License 泄露、`totalBytes=0` 时序、ANSI 乱码），版本升至 1.10.2。
- 另一环境复测建议优先覆盖：可牺牲设备的完整授权写入/重启、X Mee 实机、真实断网续传、存量 `app_config` 授权设备。
- 当前测试设备 10.0.0.93 的真实授权未被修改；测试产生的 `/userdata/upload_test.txt`、`/userdata/pkg_test.bin`、`/tmp/test.sh`、手机 Download 测试文件已清理。
