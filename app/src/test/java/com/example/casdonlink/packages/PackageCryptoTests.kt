package com.example.casdonlink.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class PackageCryptoTests {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/pkg-test.clpkg")!!.use { it.readBytes() }

    @Test
    fun `pbkdf2 hmac sha256 matches known vectors`() {
        val salt = "salt".toByteArray(Charsets.UTF_8)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(PackageCrypto.deriveKey("password".toCharArray(), salt, 1))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(PackageCrypto.deriveKey("password".toCharArray(), salt, 2))
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(PackageCrypto.deriveKey("password".toCharArray(), salt, 4096))
        )
    }

    @Test
    fun `decrypts package produced by packaging tool`() {
        val temp = File.createTempFile("clpkg-test", ".zip")
        try {
            PackageCrypto.decryptToZip(
                ByteArrayInputStream(fixture()),
                "Test-Pass-123".toCharArray(),
                temp
            )
            ZipFile(temp).use { zip ->
                val manifest = zip.getEntry("manifest.json")
                assertTrue(manifest != null)
                val text = zip.getInputStream(manifest).use { it.readBytes() }.toString(Charsets.UTF_8)
                assertTrue(text.contains("verify-encrypted"))
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `wrong password fails and removes partial output`() {
        val temp = File.createTempFile("clpkg-bad", ".zip")
        try {
            val error = runCatching {
                PackageCrypto.decryptToZip(
                    ByteArrayInputStream(fixture()),
                    "wrong-password".toCharArray(),
                    temp
                )
            }.exceptionOrNull()
            assertTrue(error is PackageDecryptException)
            assertTrue(!temp.exists())
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `bare zip is rejected`() {
        val temp = File.createTempFile("clpkg-bare", ".zip")
        try {
            val error = runCatching {
                PackageCrypto.decryptToZip(
                    ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)),
                    "Test-Pass-123".toCharArray(),
                    temp
                )
            }.exceptionOrNull()
            assertTrue(error is PackageDecryptException)
            assertTrue(!temp.exists())
        } finally {
            temp.delete()
        }
    }
}
