package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `UploadService` is closed to other apps: the main manifest (and so the release build) declares it
 * `exported="false"`, and only the debug overlay exports it, for the adb trigger. The app's data stays out of
 * backups and device transfers, as PRIVACY.md says. Unit tests run in the module directory.
 */
class ManifestTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    /** The `<service>` elements of the manifest of [sourceSet], by `android:name`. */
    private fun services(sourceSet: String): Map<String, Element> {
        val f = File("src/$sourceSet/AndroidManifest.xml")
        assertTrue("$f not found (tests run in the app module)", f.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(f).getElementsByTagName("service")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttributeNS(androidNs, "name") to e
        }
    }

    private fun exported(e: Element?): String? = e?.takeIf { it.hasAttributeNS(androidNs, "exported") }
        ?.getAttributeNS(androidNs, "exported")

    @Test
    fun main_uploadService_notExported() {
        assertEquals("false", exported(services("main")[".UploadService"]))
    }

    @Test
    fun debug_exportsUploadService_replacingTheMainValue() {
        val e = services("debug")[".UploadService"]
        assertEquals("true", exported(e))
        assertEquals("android:exported", e?.getAttributeNS(toolsNs, "replace"))
    }

    @Test
    fun debug_uploadService_keepsTheMainForegroundServiceType() {
        // Repeated only for lint's ForegroundServiceType check; a different value would fail the manifest merge.
        val type = { set: String -> services(set)[".UploadService"]?.getAttributeNS(androidNs, "foregroundServiceType") }
        assertEquals("connectedDevice", type("main"))
        assertEquals(type("main"), type("debug"))
    }

    @Test
    fun debug_exportsNothingElse() {
        val others = services("debug").filter { (name, e) -> name != ".UploadService" && exported(e) == "true" }
        assertEquals(emptySet<String>(), others.keys)
    }

    @Test
    fun onlyDebugOverlayExistsBesideMain() {
        // A release (or other build type) overlay could export the service again.
        val overlays = File("src").listFiles().orEmpty()
            .filter { File(it, "AndroidManifest.xml").isFile }.map { it.name }.toSet()
        assertEquals(setOf("main", "debug"), overlays)
    }

    @Test
    fun noBackup_noDeviceTransfer() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val app = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup")) // Android 11 and older
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules")) // 12+
        val rules = factory.newDocumentBuilder().parse(File("src/main/res/xml/data_extraction_rules.xml"))
        // Each domain on its own: the backup walks files, databases and preferences apart from the root tree.
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val e = rules.getElementsByTagName(section).item(0) as Element
            assertEquals(section, 0, e.getElementsByTagName("include").length)
            val excludes = e.getElementsByTagName("exclude")
            val all = (0 until excludes.length).map { excludes.item(it) as Element }
                .filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            assertEquals(section, domains, all)
        }
    }
}
