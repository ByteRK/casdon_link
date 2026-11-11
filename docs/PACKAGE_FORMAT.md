# 加密修复包格式（`.clpkg`）

从 2.0.0 起，修复包不再以裸 ZIP 分发，而是加密容器 `.clpkg`。容器内部仍然是
[技术方案第 5 节](TECHNICAL_DESIGN.md) 约定的 ZIP：根目录 `manifest.json` + 相对路径资源。
App 只接受 `.clpkg`，导入时输入密码；解密后的 ZIP 继续走原有的路径/大小/清单校验。

## 1. 容器布局

所有整数字段为大端。

| 偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 5 | magic，ASCII `CLPKG` |
| 5 | 1 | 容器版本，当前为 `1` |
| 6 | 4 | PBKDF2 迭代次数（uint32） |
| 10 | 16 | salt |
| 26 | 12 | AES-GCM nonce |
| 38 | N | AES-256-GCM 密文 |
| 38+N | 16 | GCM tag |

前 38 字节同时作为 GCM 的 AAD，篡改迭代次数/salt/nonce 会直接导致解密失败。

## 2. 密钥派生与加密

- KDF：PBKDF2-HMAC-SHA256，默认 210,000 次；解密按头部记录执行，接受 100,000–2,000,000。
- 密钥：32 字节；密码按 UTF-8 原样参与 KDF。
- 加密：AES-256-GCM，128-bit tag，12 字节随机 nonce，16 字节随机 salt。
- 明文：完整 ZIP 字节流。
- 密码错误或密文被篡改时，GCM tag 校验失败，App 显示“密码错误或修复包已损坏”。

## 3. 打包与解包

脚本（Windows PowerShell 7；底层为 `PackageTool.java`，通过 JDK 11+ 单文件源码运行）：

```powershell
# 目录 -> .clpkg（密码交互输入）
pwsh tools/pack-repair-package.ps1 -Source .\my-package -Output .\my-package.clpkg

# 旧 ZIP -> .clpkg（迁移旧包）
pwsh tools/pack-repair-package.ps1 -Source .\old-package.zip -Output .\old-package.clpkg

# 解包到目录
pwsh tools/unpack-repair-package.ps1 -InputPath .\my-package.clpkg -OutputDirectory .\my-package

# 只还原 ZIP
pwsh tools/unpack-repair-package.ps1 -InputPath .\my-package.clpkg -OutputZip .\my-package.zip
```

- 脚本默认交互输入密码并二次确认；`-Password` 仅用于自动化，会进入命令历史，不推荐。
- 底层 Java 工具也支持环境变量 `CASDON_PKG_PASSWORD`，PowerShell 包装脚本用它在父进程与 Java 之间传递密码，不写入命令行。
- `-Iterations` 可调整打包迭代次数（默认 210000）。
- 输出已存在时需显式加 `-Force`。
- 旧 ZIP 会保留目录条目；App 导入时跳过目录条目，不影响执行。

## 4. App 导入流程

1. 在修复包页选择 `.clpkg`（系统文件选择器）。
2. 弹窗输入修复包密码；密码只在当前页面内存中使用，不落盘。
3. 流式解密到 `cacheDir` 临时 ZIP，成功后立即交给 `PackageImporter` 做清单校验与解压。
4. 导入失败（密码错误、不是 `.clpkg`、ZIP/清单非法）不退出页面，可重新输入密码或重新选择文件。
5. 执行开始后**不提供主动停止**；失败后弹窗让使用者选择“从失败处继续”或“从头开始”。
6. 解密后的包目录保留最近 5 个（`filesDir/repair_packages/pkg_*`），更早的自动清理。

## 5. 兼容性与迁移

- 1.x 的裸 ZIP 不再被 App 接受；请用打包脚本转换。转换会保留原 ZIP 内容。
- 容器版本未知时 App 拒绝导入；后续如调整 KDF/字段，需升容器版本并保留旧版解密分支。
- 密码丢失无法恢复包内容；请保留原始目录或 ZIP 源，重新打包。

## 6. 安全说明

- AES-GCM 同时提供机密性、完整性和“知道密码的人”的认证；当前**没有独立的发布者签名**。如果以后需要区分打包人/吊销某位打包者，应增加 Ed25519/RSA 签名并在 App 校验。
- 密码是唯一信任边界：请使用足够强的密码，通过安全渠道分发，并定期轮换；不要把密码写进脚本或提交到仓库。
- App 解压后的缓存目录是明文（应用私有目录）；有 root/ADB 权限的人仍可读取。打包文件本身可以安全地通过聊天工具/网盘传递。
- 不要把 `.clpkg`、密码或测试密钥提交到 Git。
