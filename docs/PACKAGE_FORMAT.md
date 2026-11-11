# 官方签名修复包格式（`.clpkg` v2）

从 3.0.0 起，修复包只接受官方签名的 `.clpkg` v2 容器：

- **官方签名**：RSA-3072 + SHA-256，私钥只在官方打包环境，公钥内置在 App；
- **导入无需密码**：App 先验签，再用包内随机 DEK 解密；
- **v1 密码包不再支持**：旧包必须重新打包（v1 从未正式投入使用）；
- 容器内部仍是 ZIP：根目录 `manifest.json` + 相对路径资源，规则见[技术方案第 5 节](TECHNICAL_DESIGN.md)。

## 1. 安全模型

“只允许官方包执行”的边界是 **RSA 签名**，不是加密：

- App 内置官方公钥；导入时先验证签名，失败直接拒绝，不执行任何内容。
- 打包私钥不在 App、不在仓库；即使有人逆向 APK 拿到公钥/加密密钥，也无法伪造或修改出能通过验签的包。
- 载荷使用随机 DEK + 由公钥派生的 KEK 加密，只能防止“随手用解压软件打开”，**不能**阻止逆向 APK 的人解密官方包；若还要求内容保密，需要密码或服务器下发密钥。
- 公钥可放仓库/APK；私钥必须离线或放在受控机器，并做好备份。私钥泄露等于官方签名能力泄露。

## 2. 容器布局

所有整数字段为大端。

| 偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 6 | magic，ASCII `CLPKG2` |
| 6 | 1 | 容器版本，当前为 `2` |
| 7 | 4 | `sigKeyId`：公钥 DER 的 SHA-256 前 4 字节 |
| 11 | 12 | DEK 包裹 nonce |
| 23 | 48 | `wrappedDek`：AES-256-GCM(KEK, DEK) 密文 32 + tag 16 |
| 71 | 12 | 载荷 nonce |
| 83 | 32 | 载荷密文 SHA-256 |
| 115 | 384 | RSA-3072 签名，覆盖偏移 0–114 |
| 499 | N | 载荷密文：AES-256-GCM(DEK, ZIP) |

- `KEK = SHA-256("CASDON-PKG-V2-KEK" || 公钥 DER)`，32 字节。
- DEK 每包随机 32 字节；两个 nonce 各 12 字节随机。
- DEK 包裹 AAD = 偏移 0–22；载荷 AAD = 偏移 0–82。
- 签名覆盖前 115 字节（含 `payloadSha256` 与 `wrappedDek`）；载荷密文被签名中的 hash 绑定。
- App 验签成功后才解包 DEK、解密载荷；解密后再校验 hash 与 GCM tag。

## 3. 密钥

- 生成（RSA-3072）：

  ```bash
  python -m pip install -r tools/requirements.txt
  python tools/generate_package_keys.py
  ```

  默认输出：

  - `tools/keys/package-signing-private.pem`：私钥，**不提交 Git**，移到离线环境保管；
  - `app/src/main/assets/package-signing-public.pem`：公钥，随 App 构建。

- 换密钥时用 `--force` 重新生成，重新构建 App，并用新私钥重新打包；旧包会因 `sigKeyId` 不匹配被拒绝。需要同时支持新旧包时，再扩展为多公钥列表。
- 私钥丢失无法再生成能通过现有 App 验签的包；请备份。

## 4. 打包与解包（Python，跨平台）

依赖 Python 3.8+ 和 `cryptography`：

```bash
python -m pip install -r tools/requirements.txt

# 目录 -> .clpkg（默认读取 tools/keys/package-signing-private.pem）
python tools/pack_repair_package.py --source ./my-package --output ./my-package.clpkg

# 旧 ZIP -> 官方签名 .clpkg
python tools/pack_repair_package.py --source ./old-package.zip --output ./old-package.clpkg

# 解包到目录（默认用 App 内的公钥验签）
python tools/unpack_repair_package.py --input ./my-package.clpkg --output-dir ./my-package

# 只还原 ZIP
python tools/unpack_repair_package.py --input ./my-package.clpkg --output-zip ./my-package.zip
```

- 输出已存在时加 `--force`。
- `unpack` 会先验签，再解密；签名或内容校验失败直接退出。
- 打包环境只需要 Python + `cryptography`，不依赖 Java/Android SDK。

## 5. App 导入流程

1. 选择 `.clpkg`；
2. 读取 assets 中的官方公钥，按 `sigKeyId` 校验；
3. 先验签，失败提示“修复包未通过官方签名校验”；
4. 验签通过后用 KEK 解包 DEK，解密载荷并校验 hash/GCM；
5. 解密后的 ZIP 走原有路径/大小/清单校验，再展示步骤。
6. 导入和执行仍遵循：开始后不能主动停止；失败后弹窗选择“从失败处继续 / 从头开始”；缓存保留最近 5 个包目录。

## 6. 兼容性与迁移

- v1 密码包不再被 App 接受；旧包用 `pack_repair_package.py` 从 ZIP 重新打包成 v2。
- v1 从未正式投入使用，因此移除属于受控的格式切换；版本仍按不兼容变更升到 3.0.0。
- 容器版本未知时 App 拒绝导入；后续调整格式需要升版本并保留旧版解析分支。
