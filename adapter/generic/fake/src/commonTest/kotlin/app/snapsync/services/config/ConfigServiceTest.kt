package app.snapsync.services.config

import app.snapsync.fake.inMemoryFiles
import app.snapsync.model.ConfigRead
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.MembershipRead
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * The config service's answers beyond the `ConfigStore` contract's states (capability `join-event`): which file
 * answers are "unreadable" and never "not joined", what a reload keeps, and that a write or a leave that could not
 * be made is refused rather than half-done.
 */
class ConfigServiceTest {

    private val config = ConfigStoreContract.seedConfig("ConfigServiceTest")

    /** A `Files` whose every answer is [answer] — the platform's failure classes the contract cannot enter. */
    private class Answering(private val answer: FileResult<Nothing>) : Files {
        override fun read(area: FileArea, path: String): FileResult<ByteArray> = answer
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = answer
        override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = answer
        override fun delete(area: FileArea, path: String): FileResult<Unit> = answer
        override fun exists(area: FileArea, path: String): FileResult<Boolean> = answer
        override fun locate(area: FileArea, path: String): FileResult<String> = answer
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = answer
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = answer
    }

    @Test
    fun `a file that is not UTF-8 is unreadable and never not joined`() {
        val files = inMemoryFiles(shared = mutableMapOf(CONFIG_FILE_NAME to byteArrayOf(0xC3.toByte(), 0x28)))
        assertIs<ConfigRead.Unavailable>(ConfigService(files).read())
    }

    @Test
    fun `denied and failed reads are unreadable carrying their code`() {
        val denied = ConfigService(Answering(FileResult.Denied("locked", code = 257))).read()
        assertEquals(257, assertIs<ConfigRead.Unavailable>(denied).status)
        assertIs<ConfigRead.Unavailable>(ConfigService(Answering(FileResult.Failed("io"))).read())
        assertIs<ConfigRead.Unavailable>(ConfigService(Answering(FileResult.AreaUnavailable)).read())
    }

    @Test
    fun `a reload that cannot read keeps the last good membership`() {
        val shared = mutableMapOf(CONFIG_FILE_NAME to encodeConfigFile(config).encodeToByteArray())
        val denied = mutableSetOf<Pair<FileArea, String>>()
        val service = ConfigService(inMemoryFiles(shared = shared, denied = denied))
        assertEquals(config, service.config.value)

        denied += FileArea.SHARED to CONFIG_FILE_NAME
        service.reload()

        assertEquals(config, service.config.value, "a transient failure must not flip the screen to the setup gate")
        assertEquals(MembershipRead.Member(config), service.membership)
    }

    @Test
    fun `a save that cannot be written is refused`() = runTest {
        val service = ConfigService(Answering(FileResult.Denied("locked")))
        assertFailsWith<IllegalStateException> { service.save(config) }
    }

    @Test
    fun `a leave that cannot delete the file is refused and a missing file is already left`() = runTest {
        assertFailsWith<IllegalStateException> { ConfigService(Answering(FileResult.Failed("io"))).clear() }
        val absent = ConfigService(inMemoryFiles())
        absent.clear()
        assertEquals(MembershipRead.NotMember, absent.membership)
    }
}
