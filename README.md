# Casdon Link

面向售后维护人员的 Android 设备连接与批量修复工具。手机与嵌入式 Linux 设备处于同一网络时，可通过 Telnet 查看设备、管理文件、执行交互式命令，并导入修复包按步骤处理设备。

## 功能

- **连接设备**：输入设备 IP 连接，Telnet 端口默认 `23`，设备默认无账号密码；保存多个设备名称、IP 与最近使用信息，数据仅存于本机。需要登录的设备可在首次连接成功后记住用户名与密码，下次点选记录即可直接登录；密码经 Android Keystore 加密后保存，明文不落盘，长按记录可清除密码或删除记录。
- **设备状态**：查看常规 Linux 信息，包括主机名、发行版/系统信息、内核、架构、运行时间、CPU、内存、存储和网络信息。实际展示项以设备可提供的信息为准。
- **文件管理**：浏览目录树，并尽可能支持查看文本、上传、下载、删除和重命名。只读文件系统及目录权限可能限制写操作。
- **终端模式**：从设备页面切换到交互式 Shell，查看命令输出并继续输入命令。
- **修复包**：导入官方签名的 `.clpkg` 修复包（内含 ZIP 清单，导入时无需密码）；App 先验 RSA 签名，再解密并检查清单与文件，按清单顺序逐条传输文件或执行脚本；未签名/被篡改的包会被拒绝。界面只展示步骤标题与状态圆点（待执行 / 执行中 / 成功 / 失败），标题过长时跑马灯滚动，失败时在标题下方显示错误原因。执行开始后不提供主动停止，失败后可选择“从失败处继续”或“从头开始”。
- **机型规则**：连接设备后用内置的只读文件探测识别机型，命中时在设备 IP 旁显示机型名；未命中显示“未知机型”，完整功能模式下可手动选择机型。
- **补发授权码**：命中带授权配置的机型后进入独立授权页，读取当前授权与网卡 MAC，向 `MacToAuthCode` 平台申请授权码，写入设备授权文件、读回校验、`sync` 并重启，等待设备重新上线；操作结果写入本地记录，可导出完整 JSON。
- **快捷操作**：机型规则可配置分组动作队列，仅完整功能模式可见；支持危险操作二次确认、逐条执行、输出查看与停止。
- **设置与维护**：首页右上角齿轮进入设置页；集中终端字号/命令历史、设备记录与密码清理、修复包缓存、授权记录查看/导出/清空、诊断日志、授权服务检测、签名公钥/版本与隐私说明。完整功能仍保持标题 6 连击手势，不在设置页暴露解锁入口。

> 当前内置规则来自 `assets/profiles.json`；Z200Muse 的授权文件为 `/userdata/devices.xml`，CIOT 系列机型为 `/userdata/license.txt`。规则匹配只检查文件是否存在/可执行，不执行规则中的任意识别命令。

## 页面与视觉

页面保持简约，避免长段说明文字，以短标签、状态和操作为主。采用深色、现代、轻盈的科技风格：背景 `#0F172A`，卡片使用半透明 `#1E293B`，主色 `#4F9CF9`，辅助色 `#5EEAD4`，主文字 `#E5E7EB`，次级文字 `#94A3B8`。圆角卡片、细边框、柔和蓝绿光晕和充足留白构成整体视觉；动效轻微，约 200ms，活动状态可使用缓慢呼吸光效。

## 主要页面

1. **连接首页**：IP 输入、连接按钮、最近设备列表、导入修复包入口。
2. **设备概览**：连接状态、常规设备信息，以及目录和终端入口。
3. **目录浏览**：路径导航、目录树/文件列表、文件操作。
4. **终端**：交互式 Shell 输入与输出。
5. **修复包执行**：包信息、步骤状态列表与进度及执行结果。
6. **补发授权码**：机型/PID/MAC/当前授权、写入确认、执行进度与授权记录导出。
7. **快捷操作**：按机型规则分组展示调试动作，执行时展示状态与命令输出。
8. **设置**：终端字号/历史、设备记录与密码清理、修复包缓存、授权记录、诊断日志、授权服务检测、签名公钥与版本信息。

## 使用边界

本项目针对已获授权的同网段设备售后维护。目标设备已知为 BusyBox/Buildroot Linux，Telnet 服务直接提供 root shell 且无认证。Telnet 本身不加密，连接与执行命令应仅用于可信维护网络。设备根文件系统可能只读；常见可写位置包括 `/userdata` 与 `/tmp`，具体依设备而定。

补发授权码会向授权服务申请并覆盖设备授权文件，随后重启设备；设备重启后可能更换 IP，App 只能在原地址等待重新上线。授权记录中的完整授权码属于敏感信息，导出文件需按敏感文件保管。

配置包格式、Telnet 协议边界、文件传输与模块设计见 [技术方案](docs/TECHNICAL_DESIGN.md)，官方签名容器与 Python 打包工具见 [签名修复包格式](docs/PACKAGE_FORMAT.md)，设置页内容与边界见 [设置页](docs/SETTINGS.md)。1.8.0 机型规则、授权码与快捷操作的真机测试结果见 [测试报告](docs/TEST_REPORT_1.8.0.md)；1.10.1 云端变更、上传续传与故障注入测试见 [测试报告](docs/TEST_REPORT_1.10.1.md)。

## 修复包快速格式

修复包以官方签名容器 `.clpkg` 分发；App 只接受 v2，未签名/被篡改的包不会执行。容器内部仍是 ZIP，根目录放 `manifest.json`，步骤按数组顺序执行。打包环境需要 Python 3.8+ 和 `cryptography`，不依赖 Java/Android SDK：

```bash
python -m pip install -r tools/requirements.txt

# 目录 -> 官方签名 .clpkg（默认读取 tools/keys/package-signing-private.pem）
python tools/pack_repair_package.py --source ./my-package --output ./my-package.clpkg

# 旧 ZIP -> 官方签名 .clpkg
python tools/pack_repair_package.py --source ./old-package.zip --output ./old-package.clpkg

# 验签并解包查看
python tools/unpack_repair_package.py --input ./my-package.clpkg --output-dir ./my-package
```

签名私钥必须离线保管；公钥在 `app/src/main/assets/package-signing-public.pem`。格式与安全边界见 [PACKAGE_FORMAT.md](docs/PACKAGE_FORMAT.md)。示例清单：

```json
{
  "formatVersion": 1,
  "packageId": "sample-fix",
  "name": "示例修复",
  "version": "1.0.0",
  "description": "示例包",
  "steps": [
    { "id": "put-config", "type": "file", "name": "传配置", "source": "files/app.conf", "target": "/userdata/app.conf" },
    { "id": "restart", "type": "shell", "name": "重启服务", "command": "killall app; /usr/bin/app" }
  ]
}
```

文件步骤要求目标设备可通过局域网访问手机临时 HTTP 地址。建议把目标设置到 `/userdata` 等设备确认可写的位置；`/tmp` 为临时存储，重启后通常清空。

