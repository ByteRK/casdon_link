#!/usr/bin/env python3
"""把修复包目录或 ZIP 打包成官方签名的加密 .clpkg。

用法：
    python tools/pack_repair_package.py --source ./my-package --output ./my-package.clpkg
    python tools/pack_repair_package.py --source ./old-package.zip --output ./old-package.clpkg

密钥：
    默认读取 tools/keys/package-signing-private.pem。
    私钥只用于签名，必须离线/受控保管，不能提交到 Git。

依赖：
    python -m pip install -r tools/requirements.txt
"""
import argparse
import hashlib
import io
import os
import sys
import zipfile
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8")
from typing import Optional

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit(
        "缺少 cryptography。请先执行：\n"
        "  python -m pip install -r tools/requirements.txt"
    )

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PRIVATE = ROOT / "tools/keys/package-signing-private.pem"

MAGIC = b"CLPKG2"
VERSION = 2
SIGNED_BYTES = 115          # magic(6)+version(1)+keyId(4)+dekNonce(12)+wrappedDek(48)+payloadNonce(12)+payloadSha(32)
SIGNATURE_BYTES = 384       # RSA-3072
HEADER_BYTES = SIGNED_BYTES + SIGNATURE_BYTES
WRAP_AAD_BYTES = 23         # magic+version+keyId+dekNonce
PAYLOAD_AAD_BYTES = 83      # WRAP_AAD_BYTES+wrappedDek+payloadNonce
MAX_PLAIN_BYTES = 256 * 1024 * 1024
KEK_LABEL = b"CASDON-PKG-V2-KEK"


def build_zip(source: Path, exclude: Optional[Path]) -> bytes:
    if source.is_dir():
        if not (source / "manifest.json").is_file():
            raise SystemExit(f"源目录根目录缺少 manifest.json：{source}")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(source.rglob("*")):
                if not path.is_file():
                    continue
                if exclude is not None and path.resolve() == exclude.resolve():
                    continue
                relative = path.relative_to(source).as_posix()
                if relative.startswith("/") or ".." in Path(relative).parts:
                    raise SystemExit(f"源目录包含不安全路径：{relative}")
                archive.write(path, arcname=relative)
        return buffer.getvalue()

    data = source.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = {item.filename.replace("\\", "/") for item in archive.infolist()}
    if "manifest.json" not in names:
        raise SystemExit("ZIP 根目录缺少 manifest.json")
    return data


def build_container(plain: bytes, private_key) -> tuple:
    if len(plain) > MAX_PLAIN_BYTES:
        raise SystemExit("修复包明文超过 256 MB 上限")

    public_key = private_key.public_key()
    public_der = public_key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    key_id = int.from_bytes(hashlib.sha256(public_der).digest()[:4], "big")
    kek = hashlib.sha256(KEK_LABEL + public_der).digest()

    dek = os.urandom(32)
    dek_nonce = os.urandom(12)
    payload_nonce = os.urandom(12)

    wrap_aad = MAGIC + bytes([VERSION]) + key_id.to_bytes(4, "big") + dek_nonce
    wrapped_dek = AESGCM(kek).encrypt(dek_nonce, dek, wrap_aad)
    payload_aad = wrap_aad + wrapped_dek + payload_nonce
    ciphertext = AESGCM(dek).encrypt(payload_nonce, plain, payload_aad)
    payload_sha = hashlib.sha256(ciphertext).digest()

    signed = payload_aad + payload_sha
    signature = private_key.sign(signed, padding.PKCS1v15(), hashes.SHA256())
    if len(signature) != SIGNATURE_BYTES:
        raise SystemExit(f"签名长度异常：{len(signature)}（需要 RSA-3072）")
    header = signed + signature
    assert len(header) == HEADER_BYTES
    return header + ciphertext, key_id


def main() -> None:
    parser = argparse.ArgumentParser(description="打包官方签名加密修复包（.clpkg）")
    parser.add_argument("--source", required=True, help="修复包目录或旧 ZIP")
    parser.add_argument("--output", help="输出 .clpkg 路径")
    parser.add_argument("--private-key", default=str(DEFAULT_PRIVATE), help="签名私钥 PEM")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的输出")
    args = parser.parse_args()

    source = Path(args.source).resolve()
    if not source.exists():
        raise SystemExit(f"源路径不存在：{source}")
    output = Path(args.output).resolve() if args.output else (
        source.with_name(source.name + ".clpkg") if source.is_dir()
        else source.with_suffix(".clpkg")
    )
    if output.exists() and not args.force:
        raise SystemExit(f"输出已存在：{output}（使用 --force 覆盖）")

    private_path = Path(args.private_key).resolve()
    if not private_path.is_file():
        raise SystemExit(f"找不到签名私钥：{private_path}")
    private_key = serialization.load_pem_private_key(private_path.read_bytes(), password=None)

    plain = build_zip(source, output)
    container, key_id = build_container(plain, private_key)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(container)

    print(f"已生成：{output}")
    print(f"签名密钥 ID：{key_id:08x}")
    print(f"明文 {len(plain)} 字节 -> 加密包 {len(container)} 字节")
    print(f"SHA-256：{hashlib.sha256(container).hexdigest()}")


if __name__ == "__main__":
    main()
