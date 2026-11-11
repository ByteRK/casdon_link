#!/usr/bin/env python3
"""验证并解密官方签名的 .clpkg，还原为 ZIP 或目录。

用法：
    python tools/unpack_repair_package.py --input ./my-package.clpkg --output-dir ./my-package
    python tools/unpack_repair_package.py --input ./my-package.clpkg --output-zip ./my-package.zip

默认使用 app/src/main/assets/package-signing-public.pem 验签。

依赖：
    python -m pip install -r tools/requirements.txt
"""
import argparse
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8")

try:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit(
        "缺少 cryptography。请先执行：\n"
        "  python -m pip install -r tools/requirements.txt"
    )

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PUBLIC = ROOT / "app/src/main/assets/package-signing-public.pem"

MAGIC = b"CLPKG2"
VERSION = 2
SIGNED_BYTES = 115
SIGNATURE_BYTES = 384
HEADER_BYTES = SIGNED_BYTES + SIGNATURE_BYTES
WRAP_AAD_BYTES = 23
PAYLOAD_AAD_BYTES = 83
KEK_LABEL = b"CASDON-PKG-V2-KEK"


def load_public_key(path: Path):
    if not path.is_file():
        raise SystemExit(f"找不到公钥：{path}")
    public_key = serialization.load_pem_public_key(path.read_bytes())
    public_der = public_key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    key_id = int.from_bytes(hashlib.sha256(public_der).digest()[:4], "big")
    kek = hashlib.sha256(KEK_LABEL + public_der).digest()
    return public_key, key_id, kek


def decrypt(container: bytes, public_key, key_id: int, kek: bytes) -> bytes:
    if len(container) < HEADER_BYTES + 16:
        raise SystemExit("不是有效的 .clpkg（长度不足）")
    if container[:6] != MAGIC:
        raise SystemExit("不是有效的 .clpkg（缺少 CLPKG2 标识）")
    if container[6] != VERSION:
        raise SystemExit(f"不支持的容器版本：{container[6]}")

    actual_key_id = int.from_bytes(container[7:11], "big")
    if actual_key_id != key_id:
        raise SystemExit(
            f"修复包签名密钥不匹配：包为 {actual_key_id:08x}，当前公钥为 {key_id:08x}"
        )

    signed = container[:SIGNED_BYTES]
    signature = container[SIGNED_BYTES:HEADER_BYTES]
    try:
        public_key.verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())
    except Exception:
        raise SystemExit("修复包未通过官方签名校验")

    dek_nonce = container[11:23]
    wrapped_dek = container[23:71]
    payload_nonce = container[71:83]
    payload_sha = container[83:115]
    ciphertext = container[HEADER_BYTES:]
    if hashlib.sha256(ciphertext).digest() != payload_sha:
        raise SystemExit("修复包内容校验失败（密文 hash 不一致）")

    try:
        dek = AESGCM(kek).decrypt(dek_nonce, wrapped_dek, container[:WRAP_AAD_BYTES])
        return AESGCM(dek).decrypt(payload_nonce, ciphertext, container[:PAYLOAD_AAD_BYTES])
    except InvalidTag:
        raise SystemExit("修复包解密失败（密钥或内容损坏）")


def extract_zip(plain: bytes, directory: Path, force: bool) -> None:
    if directory.exists():
        if not force and any(directory.iterdir()):
            raise SystemExit(f"输出目录非空：{directory}（使用 --force 覆盖）")
    else:
        directory.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(io.BytesIO(plain)) as archive:
        for item in archive.infolist():
            name = item.filename.replace("\\", "/")
            if name.startswith("/") or ".." in Path(name).parts:
                raise SystemExit(f"ZIP 包含不安全路径：{name}")
            target = (directory / name).resolve()
            if directory.resolve() not in target.parents and target != directory.resolve():
                raise SystemExit(f"ZIP 路径越界：{name}")
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, target.open("wb") as destination:
                    destination.write(source.read())


def manifest_summary(plain: bytes) -> str:
    try:
        with zipfile.ZipFile(io.BytesIO(plain)) as archive:
            text = archive.read("manifest.json").decode("utf-8")
        manifest = json.loads(text)
        return (
            f"{manifest.get('name', 'manifest.json')} "
            f"{manifest.get('version', '')} "
            f"({manifest.get('packageId', '')})"
        ).strip()
    except Exception:
        return "manifest.json"


def main() -> None:
    parser = argparse.ArgumentParser(description="验签并解包 .clpkg")
    parser.add_argument("--input", required=True, help=".clpkg 文件")
    parser.add_argument("--output-dir", help="解包目录")
    parser.add_argument("--output-zip", help="只还原 ZIP")
    parser.add_argument("--public-key", default=str(DEFAULT_PUBLIC), help="验签公钥 PEM")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的输出")
    args = parser.parse_args()
    if args.output_dir and args.output_zip:
        raise SystemExit("--output-dir 与 --output-zip 只能二选一")

    input_path = Path(args.input).resolve()
    if not input_path.is_file():
        raise SystemExit(f"输入文件不存在：{input_path}")
    output_dir = Path(args.output_dir).resolve() if args.output_dir else None
    output_zip = Path(args.output_zip).resolve() if args.output_zip else None
    output_dir = output_dir or (
        input_path.with_name(input_path.stem + "_unpacked") if not output_zip else None
    )

    public_key, key_id, kek = load_public_key(Path(args.public_key).resolve())
    plain = decrypt(input_path.read_bytes(), public_key, key_id, kek)

    if output_zip:
        if output_zip.exists() and not args.force:
            raise SystemExit(f"输出已存在：{output_zip}（使用 --force 覆盖）")
        output_zip.parent.mkdir(parents=True, exist_ok=True)
        output_zip.write_bytes(plain)
        print(f"已还原 ZIP：{output_zip}")
    else:
        extract_zip(plain, output_dir, args.force)
        print(f"已解包到：{output_dir}")
    print(f"清单：{manifest_summary(plain)}")


if __name__ == "__main__":
    main()
