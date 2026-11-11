#!/usr/bin/env python3
"""生成修复包签名密钥对（RSA-3072）。

用法：
    python tools/generate_package_keys.py [--force]

默认输出：
    tools/keys/package-signing-private.pem   （私钥，勿提交，移入离线环境保管）
    app/src/main/assets/package-signing-public.pem（公钥，随 App 构建）

依赖：
    python -m pip install -r tools/requirements.txt
"""
import argparse
import hashlib
import os
import sys
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8")

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
except ImportError:
    sys.exit(
        "缺少 cryptography。请先执行：\n"
        "  python -m pip install -r tools/requirements.txt"
    )

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PRIVATE = ROOT / "tools/keys/package-signing-private.pem"
DEFAULT_PUBLIC = ROOT / "app/src/main/assets/package-signing-public.pem"


def main() -> None:
    parser = argparse.ArgumentParser(description="生成修复包 RSA-3072 签名密钥对")
    parser.add_argument("--private-key", default=str(DEFAULT_PRIVATE), help="私钥输出路径")
    parser.add_argument("--public-key", default=str(DEFAULT_PUBLIC), help="公钥输出路径")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的密钥")
    args = parser.parse_args()

    private_path = Path(args.private_key).resolve()
    public_path = Path(args.public_key).resolve()
    if not args.force:
        for path in (private_path, public_path):
            if path.exists():
                sys.exit(f"文件已存在：{path}（使用 --force 覆盖）")

    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_der = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    private_path.write_bytes(private_pem)
    public_path.write_bytes(public_pem)
    try:
        os.chmod(private_path, 0o600)
    except OSError:
        pass

    key_id = int.from_bytes(hashlib.sha256(public_der).digest()[:4], "big")
    print(f"私钥：{private_path}")
    print(f"公钥：{public_path}")
    print(f"签名密钥 ID：{key_id:08x}")
    print("请把私钥移到离线环境妥善保管；不要提交到 Git。")


if __name__ == "__main__":
    main()
