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
            // The one justified exception: the share / text-selection target takes text/plain CONTENT and nothing else
            // (shared_text_target_takes_only_plain_text_content below; its code is pinned by InteropArchitectureTest).
            val contentTarget = name == SHARE_TARGET
            assertTrue("$name is exported without a system-only permission", launcher || systemBound || contentTarget)
        }
    }

    /**
     * Interop (research note §1.6): the share/selection target is exported because other apps start it. It must accept
     * only ACTION_SEND / ACTION_PROCESS_TEXT of text/plain — no VIEW, no deep link, no custom action another app could use
     * to pass it a command — never be the launcher, and never be a service, receiver or provider.
     */
    @Test fun shared_text_target_takes_only_plain_text_content() {
        val app = xml("src/main/AndroidManifest.xml").children("application").single()
        val target = app.children("activity").single { it.a("name") == SHARE_TARGET }
        assertEquals("true", target.a("exported"))
        assertEquals("@android:style/Theme.NoDisplay", target.a("theme"))   // no UI of its own that could be tapped into
        val filters = target.children("intent-filter")
        assertEquals(2, filters.size)
        val actions = filters.flatMap { f -> f.children("action").map { it.a("name") } }.toSet()
        assertEquals(setOf("android.intent.action.SEND", "android.intent.action.PROCESS_TEXT"), actions)
        for (f in filters) {
            assertEquals(1, f.children("action").size)
            assertEquals(listOf("android.intent.category.DEFAULT"), f.children("category").map { it.a("name") })
            val data = f.children("data")
            assertEquals(1, data.size)
            assertEquals("text/plain", data.single().a("mimeType"))
            assertTrue("no scheme/host/path: not a deep link", data.single().a("scheme").isEmpty() && data.single().a("host").isEmpty())
        }
        for (tag in listOf("service", "receiver", "provider"))
            assertFalse("no $tag in the interop package", app.children(tag).any { it.a("name").contains(".interop.") })
    }

    private companion object { const val SHARE_TARGET = ".interop.SharedTextActivity" }

    @Test fun a_release_apk_carries_no_api_key() {
        val gradle = File("build.gradle.kts").readText()
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("buildTypes {")
        for (secret in listOf("DEFAULT_GEMINI_KEY", "DEFAULT_GROQ_KEY", "DEFAULT_ASR_ONLINE_KEY"))
            assertFalse("$secret must not be set for every build type", defaultConfig.contains("\"$secret"))
        val release = gradle.substringAfter("release {").substringBefore("proguardFiles")
        assertTrue("release blanks every key", release.contains("secretBuildFields.keys.forEach") && release.contains("\"\\\"\\\"\""))
    }
}
