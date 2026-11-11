package com.example.casdonlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 文件页/上传弹窗共用的文件名校验；中文与空格允许，路径分隔符与控制字符拒绝。 */
class FilesNameTests {

    @Test
    fun chineseAndSpaceAreAllowed() {
        assertNull(validateFileName("测试 上传.txt"))
    }

    @Test
    fun normalAsciiIsAllowed() {
        assertNull(validateFileName("release_v1.2.3.bin"))
    }

    @Test
    fun blankAndDotsAreRejected() {
        assertEquals("名称不能为空", validateFileName(""))
        assertEquals("名称不能为空", validateFileName("   "))
        assertEquals("名称无效", validateFileName("."))
        assertEquals("名称无效", validateFileName(".."))
    }

    @Test
    fun slashIsRejected() {
        assertEquals("名称不能包含 /", validateFileName("../etc/passwd"))
        assertEquals("名称不能包含 /", validateFileName("dir/name"))
    }

    @Test
    fun controlCharactersAreRejected() {
        assertEquals("名称包含控制字符", validateFileName("bad\u0000name"))
        assertEquals("名称包含控制字符", validateFileName("line\nbreak"))
    }
}
