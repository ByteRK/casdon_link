package com.example.casdonlink.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {

    private fun check(path: String, operation: FileOperation = FileOperation.EXECUTABLE) =
        FileCheck(path, operation)

    private fun profile(
        id: String,
        priority: Int,
        all: List<FileCheck>,
        none: List<FileCheck> = emptyList()
    ) = DeviceProfile(
        id = id,
        name = id,
        priority = priority,
        match = ProfileMatch(all, none),
        license = null,
        quickActions = emptyList()
    )

    @Test
    fun `all and none conditions are combined`() {
        val app = check("/userdata/app/Z200Muse")
        val marker = check("/etc/z200", FileOperation.EXISTS)
        val other = check("/userdata/app/other")
        val profiles = listOf(profile("base", 10, listOf(app, marker), listOf(other)))
        val checks = mapOf(app to true, marker to true, other to false)

        val selected = ProfileMatcher.select(profiles, checks).selected
        assertEquals("base", selected?.id)
    }

    @Test
    fun `highest priority wins and conflicting profiles are reported`() {
        val app = check("/userdata/app/Z200Muse")
        val profiles = listOf(
            profile("low", 10, listOf(app)),
            profile("high", 100, listOf(app)),
            profile("rejected", 200, listOf(app), listOf(check("/etc/rejected", FileOperation.EXISTS)))
        )
        val selection = ProfileMatcher.select(
            profiles,
            mapOf(app to true, check("/etc/rejected", FileOperation.EXISTS) to true)
        )
        assertEquals("high", selection.selected?.id)
        assertEquals(listOf("high", "low"), selection.matched.map { it.id })
    }

    @Test
    fun `unknown device selects nothing`() {
        val app = check("/userdata/app/Z200Muse")
        val selection = ProfileMatcher.select(listOf(profile("p", 1, listOf(app))), mapOf(app to false))
        assertNull(selection.selected)
        assertTrue(selection.matched.isEmpty())
    }

    @Test
    fun `probe builds one read-only command and parses indexed results`() {
        val app = check("/userdata/app/Z200Muse")
        val marker = check("/userdata/app/Z200Muse.pak", FileOperation.EXISTS)
        val plan = ProfileProbe.build(listOf(profile("p", 1, listOf(app, marker))))

        assertEquals(2, plan.checks.size)
        assertTrue(plan.command.contains("[ -x '/userdata/app/Z200Muse' ]"))
        assertTrue(plan.command.contains("[ -e '/userdata/app/Z200Muse.pak' ]"))
        assertTrue(plan.command.endsWith(" true"))

        val result = ProfileProbe.parse("noise\nCLP0=1\r\nCLP1=0\n", plan.checks)
        assertEquals(true, result[app])
        assertEquals(false, result[marker])
    }

    @Test
    fun `license template renders and parses the same values`() {
        val template = "<sections>\n<section name=\"app_config\">\n" +
            "<item name=\"DeviceId\">{{did}}</item>\n" +
            "<item name=\"License\">{{license}}</item>\n" +
            "<item name=\"ProductId\">{{pid}}</item>\n" +
            "</section>\n</sections>"
        val read = LicenseReadSpec(
            pidPattern = "<item name=\"ProductId\">([^<]*)</item>",
            didPattern = "<item name=\"DeviceId\">([^<]*)</item>",
            licensePattern = "<item name=\"License\">([^<]*)</item>"
        )
        val content = LicenseText.render(template, "VTrRZRWy", "DID-1", "LIC-1")
        val parsed = LicenseText.parse(content, read)

        assertEquals("VTrRZRWy", parsed?.pid)
        assertEquals("DID-1", parsed?.did)
        assertEquals("LIC-1", parsed?.license)
        assertEquals("LICE…3456", LicenseText.mask("LICENSE-123456"))
        assertEquals("********", LicenseText.mask("short"))
    }

    @Test
    fun `cdata wrapped license template renders and parses the same values`() {
        val template = "<sections>\n<section name=\"init\">\n" +
            "<item name=\"pid\"><![CDATA[{{pid}}]]></item>\n" +
            "<item name=\"uuid\"><![CDATA[{{did}}]]></item>\n" +
            "<item name=\"key\"><![CDATA[{{license}}]]></item>\n" +
            "<item name=\"link\"></item>\n" +
            "<item name=\"sn\"></item>\n" +
            "</section>\n</sections>"
        val read = LicenseReadSpec(
            pidPattern = "<item name=\"pid\">([^<]*)</item>",
            didPattern = "<item name=\"uuid\">([^<]*)</item>",
            licensePattern = "<item name=\"key\">([^<]*)</item>"
        )
        val content = LicenseText.render(template, "vfIS2vgn", "DID-X", "SN-X")
        val parsed = LicenseText.parse(content, read)

        assertEquals("vfIS2vgn", parsed?.pid)
        assertEquals("DID-X", parsed?.did)
        assertEquals("SN-X", parsed?.license)
    }
}
