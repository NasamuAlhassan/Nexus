package com.tekyerema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MASVS-STORAGE / MASVS-PLATFORM guards on the shipped configuration (read from the module's sources; the JVM test runs
 * in the app module directory): no backup or device transfer of app data, no exported entry point beyond the launcher
 * and system-bound services, no debug hook in the main manifest, and no API key baked into a release APK.
 */
class ManifestHardeningTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(File(path)).documentElement
    }
    private fun Element.children(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    private fun Element.a(name: String): String = getAttributeNS(androidNs, name)

    @Test fun app_data_never_leaves_through_backup_or_device_transfer() {
        val app = xml("src/main/AndroidManifest.xml").children("application").single()
        assertEquals("false", app.a("allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.a("dataExtractionRules"))
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = rules.children(section).single()
            assertTrue("$section must not include anything", s.children("include").isEmpty())
            val excluded = s.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            for (d in listOf("root", "file", "database", "sharedpref", "device_root", "device_file", "device_database", "device_sharedpref"))
                assertTrue("$section excludes $d", d in excluded)
        }
    }

    @Test fun only_the_launcher_and_system_bound_services_are_exported() {
        val app = xml("src/main/AndroidManifest.xml").children("application").single()
        val components = listOf("activity", "service", "receiver", "provider").flatMap { app.children(it) }
        assertFalse("the ADB test receiver lives in the DEBUG manifest only",
            components.any { it.a("name").contains("RunGoalReceiver") })
        for (c in components.filter { it.a("exported") == "true" }) {
            val name = c.a("name")
            val launcher = c.children("category").any { it.a("name") == "android.intent.category.LAUNCHER" }
            val systemBound = c.a("permission").startsWith("android.permission.BIND_")
            assertTrue("$name is exported without a system-only permission", launcher || systemBound)
        }
    }

    @Test fun a_release_apk_carries_no_api_key() {
        val gradle = File("build.gradle.kts").readText()
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("buildTypes {")
        for (secret in listOf("DEFAULT_GEMINI_KEY", "DEFAULT_GROQ_KEY", "DEFAULT_ASR_ONLINE_KEY"))
            assertFalse("$secret must not be set for every build type", defaultConfig.contains("\"$secret"))
        val release = gradle.substringAfter("release {").substringBefore("proguardFiles")
        assertTrue("release blanks every key", release.contains("secretBuildFields.keys.forEach") && release.contains("\"\\\"\\\"\""))
    }
}
