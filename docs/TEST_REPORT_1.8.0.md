# Casdon Link 1.8.0 功能测试报告

测试时间：2026-11-10 02:36 ~ 02:50；09:19 补充 ADB 复核（Asia/Shanghai）
真机测试版本：`CasdonLink_debug_V1.8.0_20261110-024016.apk`（带本地授权平台地址，构建时通过 `-PauthBaseUrl=http://10.0.0.10:18080`）
最终产物：`app/build/outputs/apk/debug/CasdonLink_debug_V1.8.0_20261110-092013.apk`（生产地址，`versionCode 10800`）

## 1. 结论摘要

- 规则引擎、机型识别、机型徽标、规则详情、完整功能开关、独立授权页、授权服务申请、设备文件写入、读回校验与 `sync` 已实测通过。
- 测试中发现并修复了一个**重启后误判“已重新上线”**的缺陷：设备重启时旧 socket 仍短暂打开，原实现直接复用旧会话，界面提前显示成功，后续命令超时。修复方式是主动关闭旧会话，并用重启前后的 `/proc/sys/kernel/random/boot_id` 校验是否真的换了启动周期。
- 设备执行 `sync; reboot` 后**没有回到原 IP 10.0.0.92**；手机 ping 不通，ARP/邻居表失败，局域网 23 端口扫描只发现一台无关设备（10.0.0.13，MAC `00:e0:1b:49:1a:13`）。因此“重启后重新上线、授权文件持久化、再次读取、快捷操作执行、导出”未能完成真机验证。
- 设备在重启后也从 USB ADB 消失（`ddcdf001e33aebdd` not found）。09:19 设备重新上线后通过 ADB 复核：**授权文件已持久化，内容与测试写入一致**，说明写入、读回校验和 `sync` 实际成功；设备已回到原 IP 10.0.0.92。
- 当前 Android 测试机不在线，无法做 App 级复测；本报告中的“重启后自动重连、快捷操作执行、导出”等仍待另一环境验证。

## 2. 测试环境

| 项目 | 值 |
| --- | --- |
| Android 测试机 | 2602BRT18C，Android 17，IP 10.0.0.70 |
| 实体机 | RK3506B EVB，Buildroot 2024.02，Linux 6.1.118 armv7l |
| 实体机原 IP / MAC | 10.0.0.92 / `14:0A:02:2C:00:2A`（wlan0） |
| 实体机应用 | `/userdata/app/Z200Muse`，测试时进程运行中 |
| 授权文件 | 测试前不存在（未授权）；目标 `/userdata/devices.xml` |
| 授权平台 | 本地 `MacToAuthCode`，`http://10.0.0.10:18080`，数据目录 `build/auth-test-data` |
| 联调 PID | `VTrRZRWy`（本地库存 2 条，测试消耗 1 条） |
| 连接方式 | App 经 Wi-Fi 连接设备 Telnet 23；PC 与手机同一 10.0.0.0/24 网段 |

## 3. 用例结果

| # | 用例 | 结果 | 说明 |
| --- | --- | --- | --- |
| 1 | 单元测试 | 通过 | `ProfileRulesTest` 5 个用例：all/none、优先级/冲突、未知、探测命令解析、授权模板渲染/解析/掩码 |
| 2 | Debug 构建 | 通过 | `:app:assembleDebug` 成功，APK 版本 1.8.0 |
| 3 | 安装/启动/版本 | 通过 | 首页页脚 `Version 1.8.0 (Build 10800)` |
| 4 | 连接实体机 | 通过 | 最近设备点击连接，`连接正常 · Telnet 23` |
| 5 | 机型识别 | 通过 | 徽标显示 `Z200Muse`；日志 `机型识别完成：Z200Muse` |
| 6 | 规则详情 | 通过 | 显示 ID、优先级、PID、网卡、授权路径、命中条件；见附件 |
| 7 | 完整功能开关 | 通过 | 关闭时仅显示“补发授权码”；6 连击 + 密码后出现“快捷操作”、交互终端、文件管理 |
| 8 | 授权页初始状态 | 通过 | 显示机型/PID/地址/授权路径；当前授权为“未授权（设备上不存在授权文件）” |
| 9 | 授权确认弹窗 | 通过 | 显示 PID、MAC `14:0A:02:2C:00:2A`、目标文件、当前未授权、设备重启提示 |
| 10 | 授权服务申请 | 通过 | 本地平台返回 `mode=assigned`，DID `DID-LOCAL-001`，License `LIC-LOCAL-001` |
| 11 | 写入 + 读回校验 + sync | 通过（重启前） | 日志显示写入、`cat` 校验、`sync` 均在重启前完成；校验通过后才会进入 reboot |
| 12 | 设备重启 | 部分通过 | 已发送 `sync; reboot`；设备随后离线，未回到原 IP |
| 13 | 重启后重新上线 | 未通过/已修复待复测 | 原实现误用旧会话提前报成功；已修复，但因设备离线未能复测 |
| 14 | 重启后授权文件持久化 | 通过（ADB 复核） | 09:19 设备重新上线，`/userdata/devices.xml` 存在（185 字节），内容为 `DID-LOCAL-001` / `LIC-LOCAL-001` / `VTrRZRWy`；见证据 |
| 15 | 已授权状态再次读取 | 阻塞 | 设备离线，无法进入授权页读取 |
| 16 | 授权记录写入 | 通过 | 授权页显示“本机已记录 1 条授权操作” |
| 17 | 授权记录导出 | 阻塞 | 授权页需要连接设备才能进入；设备离线后无法操作 SAF 导出 |
| 18 | 快捷操作入口 | 通过 | 完整功能开启后显示按钮；页面/执行未测 |
| 19 | 快捷操作执行 | 阻塞 | 设备离线，无法执行 `touch/rm/sh` 队列 |
| 20 | 未知机型/手动选机型 | 未覆盖 | 现场只有 Z200Muse 一台实体机；匹配器的未知/优先级逻辑有单元测试 |
| 21 | 库存不足 409 的 App 提示 | 部分 | 服务端 409 行为已用 API 验证；App UI 未在真机触发 |
| 22 | 已授权复用（mode=reused） | 部分 | 服务端 `LOCALAPI` 已实测 assigned → reused；App 未在真机触发 |
| 23 | 重启后 IP 变化 | 部分验证 | 设备离线约 6 小时后重新上线，仍为 10.0.0.92；本轮未观察到换址后的自动发现能力（设计上不支持） |

## 4. 关键证据

- 设备页识别与按钮：[ui-device-full.xml](test-evidence/ui-device-full.xml)、[shot-device.png](test-evidence/shot-device.png)
- 规则详情：[ui-rule-detail.xml](test-evidence/ui-rule-detail.xml)
- 授权页未授权：[ui-license-initial.xml](test-evidence/ui-license-initial.xml)
- 授权确认弹窗：[ui-license-confirm.xml](test-evidence/ui-license-confirm.xml)
- 补发成功界面（修复前）：[ui-license-result.xml](test-evidence/ui-license-result.xml)
- 重启后读取超时：[ui-license-read-timeout.xml](test-evidence/ui-license-read-timeout.xml)
- 重启后 ADB 复核原始输出：[adb-post-reboot.txt](test-evidence/adb-post-reboot.txt)
- 重启后 ADB 复核：`/userdata/devices.xml` 为 185 字节，内容：
  ```xml
  <sections>
  <section name="app_config">
  <item name="DeviceId">DID-LOCAL-001</item>
  <item name="License">LIC-LOCAL-001</item>
  <item name="ProductId">VTrRZRWy</item>
  </section>
  </sections>
  ```
  当前 `boot_id`：`0a11eaaf-a055-4002-9b42-7ad8a2eebd5f`；`/proc/uptime` 约 2711 秒，说明设备已完成一次启动周期。

### 授权流程日志（节选）

```text
02:37:30.533 发送：cat /sys/class/net/'wlan0'/address ...
02:37:30.882 发送：cat '/userdata/devices.xml' ...
02:37:50.590 向授权服务申请：PID=VTrRZRWy MAC=14:0A:02:2C:00:2A
02:37:50.630 授权服务返回：DID=DID-LOCAL-001 mode=assigned
02:37:50.648 发送：（登录凭证已隐藏，448 字符）      # base64 授权文件写入；后续版本文案改为“敏感内容已隐藏”
02:37:51.031 发送：cat '/userdata/devices.xml'        # 读回校验
02:37:51.531 发送：sync
02:37:52.335 发送：sync; reboot
02:37:57.343 等待设备重启后重新上线：10.0.0.92:23
```

### 服务端返回（本地平台）

```json
{
  "success": true,
  "message": "授权码分配成功",
  "data": {
    "pid": "VTrRZRWy",
    "mac": "14:0A:02:2C:00:2A",
    "display_code": "DID-LOCAL-001",
    "payload": { "did": "DID-LOCAL-001", "license": "LIC-LOCAL-001" },
    "mode": "assigned"
  }
}
```

## 5. 发现的问题与修复

### 5.1 重启后误判“已重新上线”（已修复，未复测）

现象：设备发出 `sync; reboot` 后，旧 TCP socket 在 5 秒内仍保持打开；`reconnectAfterReboot` 直接复用了 `isAlive()` 仍为 true 的旧会话，授权页提前显示“补发成功”。随后重新进入授权页读取时命令超时。

修复：

- 重启后主动关闭并丢弃旧会话，只接受新建立的 Telnet 握手。
- 重启前读取 `/proc/sys/kernel/random/boot_id`；重连后必须读到不同的 boot_id 才认为重启完成，否则关闭新连接继续等待。
- 旧 socket 关闭后先等待 2 秒再开始轮询，避免连回尚未关机的旧系统。
- 修复后重新构建 `CasdonLink_debug_V1.8.0_20261110-092013.apk`；由于设备已离线，未能在真机复测。

附带影响：本地记录文件 [license_records.json](test-evidence/license_records.json) 中该条记录被旧版本错误标记为 `SUCCESS`；修复后的逻辑只会在 boot_id 校验通过后标记成功，但该记录未在真机复测中重新生成。

### 5.2 本轮代码检查补充（未真机复测）

在交付前检查中又补了以下健壮性项，均通过编译和单元测试，但未在真机上复测：

- 授权文件读取与读回校验命令改为 `secret` 通道，超时日志也不会带出授权内容。
- 授权页/快捷操作页在操作中被销毁（旋转、系统回收）时解除“正在重启”标记，避免设备页永久跳过自动恢复。
- 授权页新增“检测授权服务”按钮，用 `OPTIONS /api/device/authorize` 区分平台不可达与库存不足。
- 快捷操作“停止”后显示“已停止”，不再误报“执行完成”。
- 机型探测结果不完整时直接判定识别失败，不按“缺失即 false”继续匹配。
- 授权目标路径增加“必须是文件路径”校验。

### 5.3 设备重启后离线（已恢复，需现场处理测试授权）

设备在 `sync; reboot` 后：

- 手机 ping 10.0.0.92：`Destination Host Unreachable`；
- 手机邻居表 10.0.0.92：`FAILED`；
- 局域网 23 端口扫描仅发现 10.0.0.13（无关设备，MAC `00:e0:1b:49:1a:13`）；
- USB ADB 设备 `ddcdf001e33aebdd` 不再出现。

可能原因（未确认）：设备启动未完成、Wi-Fi 未重新连接、DHCP 换址、或测试授权文件导致设备应用/网络异常。测试写入的是本地平台的假授权 `DID-LOCAL-001`，不能排除设备端应用对授权内容有额外校验。

恢复建议：

1. 现场确认设备是否上电、是否卡在启动界面；
2. 若可进入 shell/ADB，删除测试授权文件：`rm -f /userdata/devices.xml`，执行 `sync` 后重启，恢复到未授权状态；
3. 如设备换了 IP，请通过路由器/DHCP 列表确认新地址，再用 App 首页输入新 IP 连接；
4. 后续真机验证请优先使用真实授权库存，或使用设备端可接受的最小测试授权格式。

## 6. 未覆盖与风险

- 只验证了 Z200Muse 一套内置规则；YV100/YT100/B10 Pro 的二进制路径和授权路径未在对应硬件上验证。
- 未完成重启后的授权文件持久化、再次读取、复用（reused）提示、409 库存不足 UI、快捷操作执行、授权记录导出。
- 未覆盖未知机型/手动选择机型的真机 UI；匹配器的未知/优先级逻辑已有单元测试。
- 本地联调 APK 使用 `-PauthBaseUrl=http://10.0.0.10:18080`，不是生产地址；最终交付 APK 需重新构建（不带该参数）。
- 授权页的完整流程涉及覆盖设备授权文件并重启；在真实售后环境应先在测试机验证，并确认 PID 与设备铭牌一致。
- 设备重启后 IP 变化无法自动发现，这是当前设计的已知限制；本次测试实际遇到了原 IP 离线的情况。

## 7. 测试结束状态

- Android 测试机本轮已不在线，未能在设备恢复后复测 App 流程；最终 APK 已构建并通过单元测试。
- 本地授权平台已停止；测试数据保留在 `build/auth-test-data`（未纳入源码）。
- 实体机已重新上线（10.0.0.92，USB ADB `ddcdf001e33aebdd` 可用）；测试授权文件 `/userdata/devices.xml` 仍在，内容为 `DID-LOCAL-001` / `LIC-LOCAL-001`。这是**本地测试假授权**，如要恢复未授权状态，请执行：
  `rm -f /userdata/devices.xml && sync && reboot`
- 最终 APK 的 `BuildConfig.AUTH_BASE_URL_OVERRIDE` 为空，使用代码内混淆后的生产地址；原测试构建的本地地址覆盖参数不会进入最终 APK。

## 8. 建议下一步

1. 现场恢复设备（删除测试授权文件并重启），确认设备能正常上线；
2. 在设备恢复后，用修复后的 APK 复测：重启后 boot_id 变化、重新上线、授权文件读取、reused、导出、快捷操作；
3. 为 YV100/YT100/B10 Pro 准备真机或至少提供一份 `ls -l /userdata/app` 与授权文件样例，验证二进制路径；
4. 生产发布前确认 `VTrRZRWy` 在正式授权平台有库存且分发开启；本次测试使用的本地库存不作为生产库存。

另一环境的完整复测步骤、预期结果和失败处理见 [1.8.0 复测清单](RETEST_CHECKLIST_1.8.0.md)。
