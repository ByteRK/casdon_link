package com.example.casdonlink.packages

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 修复包验签/解密失败：签名不匹配、密钥未知、内容损坏或不是 `.clpkg`。 */
class PackageDecryptException(message: String) : Exception(message)

/**
 * 官方签名公钥与包级密钥。
 *
 * 公钥来自 APK assets 中的 `package-signing-public.pem`；私钥只存在于官方打包环境。
 * `keyId` 取公钥 DER 的 SHA-256 前 4 字节，用于识别签名密钥。
 *
 * 载荷加密使用随机 DEK + 由公钥派生的 KEK。公钥不是秘密，因此载荷加密只能阻止
 * 随手解压，不构成对逆向 APK 的机密性；真正的“只允许官方包”边界是 RSA 签名。
 */
class PackageVerifier private constructor(
    private val publicKey: PublicKey,
    val keyId: Int,
    private val kek: ByteArray
) {
    /** 公钥指纹前 4 字节的 8 位十六进制表示，用于设置页/排障展示。 */
    val keyIdHex: String = keyId.toUInt().toString(16).padStart(8, '0')

    fun verify(signed: ByteArray, signature: ByteArray): Boolean = runCatching {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(signed)
        verifier.verify(signature)
    }.getOrDefault(false)

    fun unwrapDek(wrapped: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(aad)
            cipher.doFinal(wrapped)
        } catch (error: Exception) {
            throw PackageDecryptException("修复包密钥解包失败")
        }
    }

    companion object {
        private const val ASSET = "package-signing-public.pem"
        private const val KEK_LABEL = "CASDON-PKG-V2-KEK"
        private const val TAG_BITS = 128

        fun fromAsset(context: Context): PackageVerifier =
            context.assets.open(ASSET).use { fromPem(it.readBytes()) }

        /** 解析 PEM 公钥；单元测试与 assets 读取共用。 */
        fun fromPem(pem: ByteArray): PackageVerifier {
            val base64 = String(pem, Charsets.US_ASCII)
                .lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .filterNot(Char::isWhitespace)
            val der = try {
                decodeBase64(base64)
            } catch (error: Exception) {
                throw PackageDecryptException("内置签名公钥格式无效")
            }
            val publicKey = try {
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            } catch (error: Exception) {
                throw PackageDecryptException("内置签名公钥无法解析")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val keyId = ByteBuffer.wrap(digest, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val kek = MessageDigest.getInstance("SHA-256")
                .digest(KEK_LABEL.toByteArray(Charsets.US_ASCII) + der)
            return PackageVerifier(publicKey, keyId, kek)
        }

        /** 纯 Kotlin Base64 解码：android.util.Base64 在本地 JVM 单元测试中不可用。 */
        private fun decodeBase64(text: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val map = IntArray(128) { -1 }
            alphabet.forEachIndexed { index, char -> map[char.code] = index }
            val output = ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (char in text) {
                if (char == '=') break
                if (char.code >= map.size) continue
                val value = map[char.code]
                if (value < 0) continue
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output.write((buffer shr bits) and 0xFF)
                }
            }
            return output.toByteArray()
        }
    }
}

/**
 * `.clpkg` v2 容器：官方签名 + 免密解密。
 *
 * 布局（全部大端）：
 * ```
 * 0    6    magic "CLPKG2"
 * 6    1    container version = 2
 * 7    4    sigKeyId（公钥 DER SHA-256 前 4 字节）
 * 11   12   DEK 包裹 nonce
 * 23   48   wrappedDek（AES-256-GCM(KEK, DEK) + tag）
 * 71   12   载荷 nonce
 * 83   32   载荷密文 SHA-256
 * 115  384  RSA-3072 签名（覆盖 0..114）
 * 499  N    载荷密文（AES-256-GCM(DEK, ZIP)）
 * ```
 * 验签在解密之前；签名有效但内容 hash/GCM 失败时仍拒绝。
 */
object PackageCrypto {
    /** 当前 `.clpkg` 容器版本；设置页展示用。 */
    const val FORMAT_VERSION = 2

    private val MAGIC = "CLPKG2".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 499
    private const val SIGNED_BYTES = 115
    private const val KEY_ID_OFFSET = 7
    private const val DEK_NONCE_OFFSET = 11
    private const val WRAPPED_DEK_OFFSET = 23
    private const val PAYLOAD_NONCE_OFFSET = 71
    private const val PAYLOAD_SHA_OFFSET = 83
    private const val PAYLOAD_SHA_BYTES = 32
    private const val WRAP_AAD_BYTES = 23
    private const val PAYLOAD_AAD_BYTES = 83
    private const val TAG_BITS = 128
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_CIPHERTEXT_BYTES = 256L * 1024 * 1024

    /**
     * 验签并解密为临时 ZIP。调用方负责删除 [output]。
     * 任何失败都会删除 [output]，避免把未通过验签的内容交给后续导入。
     */
    fun decryptToZip(input: InputStream, verifier: PackageVerifier, output: File) {
        try {
            val header = readHeader(input)
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw PackageDecryptException("不是有效的加密修复包（.clpkg）")
            }
            if (header[MAGIC.size].toInt() != FORMAT_VERSION) {
                throw PackageDecryptException("不支持的修复包版本")
            }
            val keyId = ByteBuffer.wrap(header, KEY_ID_OFFSET, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (keyId != verifier.keyId) {
                throw PackageDecryptException("修复包使用了未知的签名密钥")
            }
            val signed = header.copyOfRange(0, SIGNED_BYTES)
            val signature = header.copyOfRange(SIGNED_BYTES, HEADER_BYTES)
            if (!verifier.verify(signed, signature)) {
                throw PackageDecryptException("修复包未通过官方签名校验")
            }

            val dekNonce = header.copyOfRange(DEK_NONCE_OFFSET, DEK_NONCE_OFFSET + 12)
            val wrappedDek = header.copyOfRange(WRAPPED_DEK_OFFSET, WRAPPED_DEK_OFFSET + 48)
            val payloadNonce = header.copyOfRange(PAYLOAD_NONCE_OFFSET, PAYLOAD_NONCE_OFFSET + 12)
            val payloadSha = header.copyOfRange(PAYLOAD_SHA_OFFSET, PAYLOAD_SHA_OFFSET + PAYLOAD_SHA_BYTES)
            val dek = verifier.unwrapDek(
                wrappedDek,
                dekNonce,
                header.copyOfRange(0, WRAP_AAD_BYTES)
            )
            if (dek.size != 32) {
                dek.fill(0)
                throw PackageDecryptException("修复包密钥长度无效")
            }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(dek, "AES"),
                    GCMParameterSpec(TAG_BITS, payloadNonce)
                )
                cipher.updateAAD(header.copyOfRange(0, PAYLOAD_AAD_BYTES))
                val digest = MessageDigest.getInstance("SHA-256")
                output.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_CIPHERTEXT_BYTES) {
                            throw PackageDecryptException("修复包过大（超过 256 MB）")
                        }
                        digest.update(buffer, 0, count)
                        val chunk = cipher.update(buffer, 0, count)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    val tail = cipher.doFinal()
                    if (tail != null && tail.isNotEmpty()) out.write(tail)
                }
                if (!digest.digest().contentEquals(payloadSha)) {
                    throw PackageDecryptException("修复包内容校验失败")
                }
            } catch (error: PackageDecryptException) {
                throw error
            } catch (error: Exception) {
                throw PackageDecryptException("修复包解密失败或内容已损坏")
            } finally {
                dek.fill(0)
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun readHeader(input: InputStream): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count < 0) {
                throw PackageDecryptException("不是有效的加密修复包（.clpkg）")
            }
            offset += count
        }
        return header
    }
}
