package com.example.casdonlink.packages

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 加密修复包解密失败：密码错误、文件损坏或不是 `.clpkg`。 */
class PackageDecryptException(message: String) : Exception(message)

/**
 * 加密修复包 `.clpkg` 的容器格式与解密实现。
 *
 * 布局（全部大端）：
 * ```
 * 0   5   magic "CLPKG"
 * 5   1   container version = 1
 * 6   4   PBKDF2 迭代次数
 * 10  16  salt
 * 26  12  GCM nonce
 * 38  N   AES-256-GCM 密文
 * 38+N 16 GCM tag
 * ```
 * 明文是现有修复包 ZIP；AAD 覆盖整个 38 字节头部，防止篡改 KDF 参数。
 * 密码不落盘；KDF 使用 PBKDF2-HMAC-SHA256，输出 32 字节密钥。
 *
 * 注意：密码以 UTF-8 原样参与 KDF，打包脚本必须使用同样编码。
 */
object PackageCrypto {
    /** 新打包默认迭代次数；解密时按头部记录执行，兼容后续调整。 */
    const val ITERATIONS = 210_000

    private val MAGIC = "CLPKG".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 5 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val MAX_ENCRYPTED_BYTES = 256L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * 把加密包解密为临时 ZIP。调用方负责删除 [output]。
     *
     * GCM 的 tag 在 `doFinal` 才校验，因此中途写出的明文不可信；任何失败都会删除
     * [output]，避免把未认证内容留给后续导入流程。
     */
    fun decryptToZip(input: InputStream, password: CharArray, output: File) {
        try {
            val header = readHeader(input)
            val key = deriveKey(password, header.salt, header.iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, header.nonce)
                )
                cipher.updateAAD(header.raw)
                output.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ENCRYPTED_BYTES) {
                            throw PackageDecryptException("修复包过大（超过 256 MB）")
                        }
                        val chunk = cipher.update(buffer, 0, count)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    val tail = try {
                        cipher.doFinal()
                    } catch (error: AEADBadTagException) {
                        throw PackageDecryptException("密码错误或修复包已损坏")
                    } catch (error: GeneralSecurityException) {
                        throw PackageDecryptException(
                            "修复包解密失败：${error.message ?: error.javaClass.simpleName}"
                        )
                    }
                    if (tail != null && tail.isNotEmpty()) out.write(tail)
                }
            } finally {
                key.fill(0)
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    /** PBKDF2-HMAC-SHA256，单块输出 32 字节；internal 便于单元测试固定向量。 */
    internal fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "迭代次数必须大于 0" }
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            val u = ByteArray(32)
            val t = ByteArray(32)
            // U1 = HMAC(password, salt || INT(1))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, u.size)
            // T = U1 xor U2 xor ... xor Uc
            for (i in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in u.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t
        } finally {
            passwordBytes.fill(0)
        }
    }

    private data class Header(
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val raw: ByteArray
    )

    private fun readHeader(input: InputStream): Header {
        val raw = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < raw.size) {
            val count = input.read(raw, offset, raw.size - offset)
            if (count < 0) throw PackageDecryptException("不是有效的加密修复包（.clpkg）")
            offset += count
        }
        if (!raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw PackageDecryptException("不是有效的加密修复包（.clpkg）")
        }
        if (raw[MAGIC.size].toInt() != VERSION) {
            throw PackageDecryptException("不支持的修复包版本（${raw[MAGIC.size]}）")
        }
        val iterations = ByteBuffer.wrap(raw, MAGIC.size + 1, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw PackageDecryptException("修复包头部参数无效")
        }
        val saltOffset = MAGIC.size + 1 + 4
        val nonceOffset = saltOffset + SALT_BYTES
        return Header(
            iterations = iterations,
            salt = raw.copyOfRange(saltOffset, saltOffset + SALT_BYTES),
            nonce = raw.copyOfRange(nonceOffset, nonceOffset + NONCE_BYTES),
            raw = raw
        )
    }
}
