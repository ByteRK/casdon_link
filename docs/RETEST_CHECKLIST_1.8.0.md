# Casdon Link 1.8.0 复测清单（另一环境）

本清单用于在具备“Android 手机 + 实体机 + 授权平台库存”的环境复测 1.8.0。上一轮已完成规则识别、授权页、写入/校验/`sync`，但 Android 测试机在设备重启后离线，未完成 App 级重启重连、快捷操作和导出验证。

## 1. 前置条件

- Android 手机：最低 API 24；与实体机处于同一网段，能访问授权平台。
- 实体机：Telnet 23 可连接；`/userdata` 可写；`base64`、`sync`、`reboot` 可用。
- 授权平台：目标 PID 有可用库存且分发开启；或使用本地 `MacToAuthCode` 测试库。
- 测试机建议先恢复未授权状态：
  ```sh
  rm -f /userdata/devices.xml /userdata/license.txt
  sync
  reboot
  ```
- 安装 APK 后清除应用数据（`pm clear com.example.casdonlink`），确认完整功能开关为关闭状态。

## 2. 构建与安装

```powershell
$env:JAVA_HOME="E:\Android\Android Studio\jbr"

# 生产地址（默认，最终交付用）
.\gradlew.bat :app:assembleDebug

# 本地授权平台联调（仅 debug）
.\gradlew.bat :app:assembleDebug "-PauthBaseUrl=http://10.0.0.10:18080"
```

产物：`app/build/outputs/apk/debug/CasdonLink_debug_V1.8.0_<时间戳>.apk`。

## 3. 必测用例

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 覆盖安装并启动 | 版本 1.8.0；首页正常 |
| 2 | 连接实体机 | 设备页显示 IP、`连接正常` |
| 3 | 观察 IP 旁徽标 | 命中机型显示规则名（Z200Muse 等）；未命中显示“未知机型” |
| 4 | 点击徽标 | 显示规则 ID、PID、网卡、授权路径、命中条件；无 crash |
| 5 | 关闭完整功能 | 只显示“补发授权码”，不显示“快捷操作” |
| 6 | 首页标题连点 6 下 + 密码 | 完整功能开启；设备页出现“快捷操作”、终端、文件管理 |
| 7 | 进入补发授权码 | 显示 PID/MAC/目标路径；未授权设备显示“未授权” |
| 8 | 勾选确认并开始 | 弹出确认框，含 PID/MAC/路径/重启提示 |
| 9 | 本地平台分配 | 返回 assigned/reused；授权页进入写入、校验、`sync`、重启 |
| 10 | 重启后等待 | 设备重新上线；界面显示“补发成功”；不提前报成功 |
| 11 | 再次进入授权页 | 显示已授权 PID/DID；License 打码 |
| 12 | 再次补发同一设备 | 平台返回 `mode=reused`，不重复消耗库存 |
| 13 | 导出授权记录 | SAF 保存 JSON；字段含时间、机型、PID、MAC、DID、License、结果 |
| 14 | 快捷操作：查看/创建/删除测试文件 | `touch`/`ls`/`rm` 成功；危险操作有确认 |
| 15 | 快捷操作：运行 `/tmp/test.sh` | 有输出；失败时显示原因 |
| 16 | 快捷操作执行中点“停止” | 当前命令结束后停止后续，状态显示“已停止” |
| 17 | 授权平台无库存 / 停止分发 | App 显示平台返回的 409 文案，不误报成功 |
| 18 | 操作中旋转屏幕 / 切后台 | 不卡死；回到设备页能恢复或明确提示重连 |
| 19 | 设备重启后更换 IP | 90 秒内提示“未重新上线/可能更换 IP”，不自动扫网段 |
| 20 | 检查诊断日志 | 不出现完整 License/授权文件内容；敏感命令显示“已隐藏” |

## 4. 关键证据

- 设备页徽标与两个按钮的截图/UI dump。
- 授权确认弹窗（PID/MAC/路径）。
- 平台请求/响应日志（assigned 与 reused 各一次）。
- 设备端 `/userdata/devices.xml` 或 `/userdata/license.txt` 内容。
- 重启前后 `/proc/sys/kernel/random/boot_id`（App 内不可见，可 ADB/串口读取）。
- 导出 JSON 文件。
- 快捷操作输出与“停止”后的状态。

## 5. 常见失败处理

- **授权页显示已授权**：设备上已有测试授权；按前置条件删除文件并重启。
- **409 当前没有可分配的授权码**：平台库存不足；先在后台导入/新增该 PID 的授权码。
- **409 该 PID 已停止授权码分发**：在平台 PID 清单开启分发。
- **重启后连不上**：确认设备是否回到原 IP；如 DHCP 换址，用路由器/DHCP 列表查新地址后手动连接。App 不会自动扫网段。
- **重启后仍连旧会话/超时**：确认使用的是包含 boot_id 校验的最新 APK；查看诊断日志是否有“设备尚未完成重启，继续等待”。
- **页面旋转/返回后卡在“正在重启”**：确认已安装最新 APK（`endReboot` 修复）。

## 6. 测试后恢复

```sh
# 删除测试授权，恢复未授权状态
rm -f /userdata/devices.xml /userdata/license.txt
sync
reboot
```

如测试使用本地平台，记录文件仅在本机 `filesDir/license_records.json`，可在授权页导出后按敏感文件处理。
