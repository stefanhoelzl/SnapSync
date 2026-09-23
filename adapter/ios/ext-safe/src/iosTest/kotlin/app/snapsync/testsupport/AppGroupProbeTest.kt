@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.testsupport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.posix.chmod
import platform.posix.getuid
import kotlin.test.Test
import kotlin.test.fail

/**
 * TEMPORARY measurement probe (change `contract-app-group-stores`, tasks 1.1 and 1.2). It always fails,
 * so CI prints what it measured; it is deleted in the next commit.
 */
class AppGroupProbeTest {

    private fun readError(path: String): String = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val data = NSData.dataWithContentsOfFile(path, options = 0u, error = err.ptr)
        if (data != null) return "READ OK (${data.length} bytes)"
        val e = err.value ?: return "nil data, nil error"
        val under = e.userInfo["NSUnderlyingError"] as? NSError
        "domain=${e.domain} code=${e.code} underlying=${under?.domain}/${under?.code}"
    }

    @Test
    fun `PROBE app-group measurements`() {
        val dir = newTempDirectory()
        val out = StringBuilder("uid=${getuid()}\n")

        val asDir = "$dir/eventconfig.json"
        NSFileManager.defaultManager.createDirectoryAtPath(asDir, true, null, null)
        out.append("directory-at-path: ${readError(asDir)}\n")

        val mode000 = "$dir/mode000.json"
        writeTextFile(mode000, "{}")
        val rc = chmod(mode000, 0u)
        out.append("mode-000 file (chmod rc=$rc): ${readError(mode000)}\n")
        chmod(mode000, 0x1A4u)

        out.append("missing: ${readError("$dir/missing.json")}\n")

        val suite = "contract.probe.suite"
        val defaults = NSUserDefaults(suiteName = suite)
        defaults.setObject("v1", forKey = "k")
        val same = NSUserDefaults(suiteName = suite).stringForKey("k")
        defaults.removePersistentDomainForName(suite)
        val after = NSUserDefaults(suiteName = suite).stringForKey("k")
        out.append("suite round-trip: read-back=$same after-remove=$after\n")

        val group = NSUserDefaults(suiteName = "group.app.snapsync")
        group.setObject("g", forKey = "probe")
        out.append("group suite round-trip: ${NSUserDefaults(suiteName = "group.app.snapsync").stringForKey("probe")}\n")
        group.removeObjectForKey("probe")

        removeDirectory(dir)
        fail("PROBE RESULT\n$out")
    }
}
