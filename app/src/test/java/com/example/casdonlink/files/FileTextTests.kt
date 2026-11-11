package com.example.casdonlink.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文本预览的二进制判定：只认 NUL，避免把中文/ANSI 日志误判成二进制。 */
class FileTextTests {

    @Test
    fun nulByteIsBinary() {
        assertTrue(looksLikeBinary(byteArrayOf(0x41, 0x00, 0x42)))
    }

    @Test
    fun utf8ChineseTextIsText() {
        assertFalse(looksLikeBinary("中文 文本\n第二行".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun controlSequencesAreStillText() {
        assertFalse(looksLikeBinary("\u001B[0;0mdrwxr-xr-x\n\tfile".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun emptyIsText() {
        assertFalse(looksLikeBinary(ByteArray(0)))
    }

    @Test
    fun nulAfterSniffWindowIsIgnored() {
        val bytes = ByteArray(4096) { 'a'.code.toByte() } + byteArrayOf(0)
        assertFalse(looksLikeBinary(bytes))
    }
}
