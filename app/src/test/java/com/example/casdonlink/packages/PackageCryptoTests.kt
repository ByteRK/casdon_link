package com.example.casdonlink.packages

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class PackageCryptoTests {

    private fun verifier(): PackageVerifier = PackageVerifier.fromPem(
        javaClass.getResourceAsStream("/test-signing-public.pem")!!.use { it.readBytes() }
    )

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/pkg-v2-test.clpkg")!!.use { it.readBytes() }

    private fun decrypt(bytes: ByteArray, temp: File) {
        PackageCrypto.decryptToZip(ByteArrayInputStream(bytes), verifier(), temp)
    }

    @Test
    fun `decrypts signed package`() {
        val temp = File.createTempFile("clpkg-v2", ".zip")
        try {
            decrypt(fixture(), temp)
            ZipFile(temp).use { zip ->
                val manifest = zip.getEntry("manifest.json")
                assertTrue(manifest != null)
                val text = zip.getInputStream(manifest).use { it.readBytes() }.toString(Charsets.UTF_8)
                assertTrue(text.contains("v2-signed-test"))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `tampered ciphertext fails`() {
        val temp = File.createTempFile("clpkg-v2-bad", ".zip")
        try {
            val bytes = fixture()
            bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0x01).toByte()
            val error = runCatching { decrypt(bytes, temp) }.exceptionOrNull()
            assertTrue(error is PackageDecryptException)
            assertTrue(!temp.exists())
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `tampered signature fails`() {
        val temp = File.createTempFile("clpkg-v2-sig", ".zip")
        try {
            val bytes = fixture()
            bytes[200] = (bytes[200].toInt() xor 0x01).toByte()
            val error = runCatching { decrypt(bytes, temp) }.exceptionOrNull()
            assertTrue(error is PackageDecryptException)
            assertTrue((error as PackageDecryptException).message!!.contains("签名"))
            assertTrue(!temp.exists())
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `unknown signing key id fails`() {
        val temp = File.createTempFile("clpkg-v2-key", ".zip")
        try {
            val bytes = fixture()
            bytes[7] = 0
            bytes[8] = 0
            bytes[9] = 0
            bytes[10] = 1
            val error = runCatching { decrypt(bytes, temp) }.exceptionOrNull()
            assertTrue(error is PackageDecryptException)
            assertTrue((error as PackageDecryptException).message!!.contains("签名密钥"))
            assertTrue(!temp.exists())
        } finally {
            temp.delete()
        }
    }
}
