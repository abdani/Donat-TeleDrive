package donat.teledrive

import donat.teledrive.core.NoManifestException
import donat.teledrive.core.StorageProvider
import donat.teledrive.core.TransferState
import donat.teledrive.data.AppDatabase
import donat.teledrive.data.Drive
import donat.teledrive.data.Manifest
import donat.teledrive.telegram.AuthState
import donat.teledrive.telegram.TelegramAccount
import donat.teledrive.telegram.TelegramProvider
import it.tdlight.jni.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real Telegram, the user's logged-in session (the app must be closed). Everything happens in a temporary drive
 * "DTD Live Test (temporary)" that is deleted at the end — the user's own drives are never touched.
 * Run: gradlew jvmTest --tests donat.teledrive.LiveTest -PliveTest=true
 */
class LiveTest {
    /** Lets a test run another PC's save exactly between our pull and our write. */
    private class Hooked(val p: StorageProvider) : StorageProvider by p {
        var beforeWrite: (suspend () -> Unit)? = null
        override suspend fun writeManifest(data: ByteArray): String { beforeWrite?.let { beforeWrite = null; it() }; return p.writeManifest(data) }
    }

    private val TITLE = "DTD Live Test (temporary)"
    private val work = Files.createTempDirectory("dtd-live").toFile()
    private fun step(s: String) = println("LIVE ✓ $s")

    @Test
    fun recoveryAndSyncAgainstRealTelegram() = runBlocking<Unit> {
        assumeTrue(System.getProperty("donat.live") == "true")
        val (apiId, apiHash) = Secrets.load() ?: error("not logged in on this PC")
        val account = TelegramAccount(appDataDir)
        account.connect(apiId, apiHash)
        withTimeout(60_000) { account.auth.first { it is AuthState.Ready || it is AuthState.Failed } }
        check(account.auth.value is AuthState.Ready) { "Telegram session: ${account.auth.value}" }
        val chat = account.createDriveChannel(TITLE)
        step("test drive channel created")
        try {
            run(account, chat)
        } finally {
            cleanUp(account)
            account.close()
        }
    }

    /** Deletes every leftover TITLE drive (and its data channel), waiting out Telegram's FLOOD_WAIT. */
    @Test
    fun cleanupTemporaryDrives() = runBlocking<Unit> {
        assumeTrue(System.getProperty("donat.live") == "cleanup")
        val (apiId, apiHash) = Secrets.load() ?: error("not logged in on this PC")
        val account = TelegramAccount(appDataDir)
        account.connect(apiId, apiHash)
        withTimeout(60_000) { account.auth.first { it is AuthState.Ready || it is AuthState.Failed } }
        try { cleanUp(account) } finally { account.close() }
    }

    private suspend fun cleanUp(account: TelegramAccount) {
        while (true) {
            val left = account.findDrives().filter { it.title == TITLE }
            if (left.isEmpty()) break
            try {
                left.forEach { d ->
                    provider(account, d.chatId).deleteDrive()
                    Prefs.remove("tidy3-${d.chatId}")
                }
            } catch (e: donat.teledrive.core.RateLimitedException) {
                println("LIVE … Telegram FLOOD_WAIT ${e.retryAfterSeconds}s before deleting the test drive")
                delay(e.retryAfterSeconds * 1000 + 2000)
            }
        }
        step("test drive deleted, nothing left in the account")
    }

    private fun db(name: String): AppDatabase = openDatabase(File(work, "$name.db").path)
    private fun provider(account: TelegramAccount, chat: Long) = TelegramProvider(account, chat, File(work, "tmp").apply { mkdirs() })

    private suspend fun DriveService.drain(db: AppDatabase) = kotlinx.coroutines.coroutineScope {
        val job = launch { runTransfers() }
        withTimeout(180_000) { while (db.dao().transfers(drive.id).first().any { it.state != TransferState.COMPLETED }) delay(200) }
        job.cancel()
    }

    private suspend fun run(account: TelegramAccount, chat: Long) {
        // 1. A drive with a folder and three files.
        val db1 = db("pc1")
        val drive = Drive(newId(), TITLE, "telegram", chat.toString(), now())
        db1.dao().upsert(drive)
        val pc1 = DriveService(db1, provider(account, chat), drive, File(work, "pc1").apply { mkdirs() })
        pc1.saveManifestNow()
        pc1.createFolder("Docs", null)
        val docs = db1.dao().folders(drive.id, null).first().single()
        val content = mapOf("a.txt" to Random(1).nextBytes(3000), "b.bin" to Random(2).nextBytes(5000), "root.dat" to Random(3).nextBytes(1000))
        content.forEach { (name, bytes) -> pc1.enqueueUpload(File(work, name).apply { writeBytes(bytes) }, if (name == "root.dat") null else docs.id) }
        pc1.drain(db1)
        assertEquals(3, db1.dao().activeFiles(drive.id).first().size)
        step("uploaded 3 files (2 in a folder)")

        // 2. Nothing unlisted in a healthy drive.
        assertTrue(pc1.findUnlisted().isEmpty())
        step("healthy drive: no unlisted files")

        // 3. A file sent to the channel the way a phone would → found, added, byte-identical.
        val phone = Random(4).nextBytes(2222)
        account.sendDocument(chat, File(work, "from-phone.txt").apply { writeBytes(phone) }.path, "", {})
        val fromPhone = pc1.findUnlisted()
        assertEquals(listOf("from-phone.txt"), fromPhone.map { it.name })
        assertEquals(1, pc1.adopt(fromPhone, "Recovered"))
        val adopted = db1.dao().activeFiles(drive.id).first().single { it.name == "from-phone.txt" }
        assertContentEquals(phone, pc1.cached(adopted).readBytes())
        assertNotNull(db1.dao().file(adopted.id)!!.sha256) // first download recorded the hash
        assertTrue(pc1.findUnlisted().isEmpty())
        step("phone upload found, added to “Recovered”, downloaded byte-identical")

        // 4. Leftover of an interrupted delete → found and deleted from Telegram; the rest untouched.
        val victim = db1.dao().activeFiles(drive.id).first().single { it.name == "root.dat" }
        val victimRef = db1.dao().parts(victim.id).single().remoteRef
        db1.dao().deleteFile(victim.id)
        pc1.saveManifestNow()
        assertEquals(listOf(victimRef), pc1.findUnlisted().map { it.ref })
        assertEquals(1, pc1.deleteUnlisted(pc1.findUnlisted()))
        assertTrue(provider(account, chat).listObjects().none { it.ref == victimRef })
        assertEquals(3, provider(account, chat).listObjects().size)
        step("interrupted-delete leftover removed from Telegram, other files intact")

        // 5. Data channel deleted (e.g. on the phone) → the PC that has the index writes it again into a new channel.
        val data1 = account.dataChannels(refresh = true)[chat] ?: error("no data channel")
        account.call(TdApi.DeleteChat(data1)) // the app still has it cached — exactly like a deletion on the phone
        pc1.pull() // must not fail; notices the missing index
        pc1.saveManifestNow()
        val v = provider(account, chat).manifestVersion()
        assertNotNull(v)
        assertNotEquals(data1.toString(), v.substringBefore(':'))
        val db2 = db("pc2")
        DriveService.restore(db2, provider(account, chat), chat.toString(), "telegram", File(work, "pc2").apply { mkdirs() })
        assertEquals(
            db1.dao().activeFiles(drive.id).first().map { it.name to it.folderId }.toSet(),
            db2.dao().activeFiles(drive.id).first().map { it.name to it.folderId }.toSet(),
        )
        step("deleted data channel: index rewritten to a new channel, a new PC restores everything incl. folders")

        // 6. Two PCs saving at the same moment → both changes survive.
        val pc2Provider = Hooked(provider(account, chat))
        val pc2 = DriveService(db2, pc2Provider, db2.dao().drives().first().single(), File(work, "pc2"))
        val a1 = db1.dao().activeFiles(drive.id).first().single { it.name == "a.txt" }
        pc1.rename(a1, "a-pc1.txt")
        val b2 = db2.dao().activeFiles(drive.id).first().single { it.name == "b.bin" }
        pc2.rename(b2, "b-pc2.bin")
        pc2Provider.beforeWrite = { pc1.saveManifestNow() } // PC1 saves between PC2's pull and PC2's write
        pc2.saveManifestNow()
        val latest = Manifest.decode(provider(account, chat).readManifest()!!)
        assertEquals(setOf("a-pc1.txt", "b-pc2.bin", "from-phone.txt"), latest.files.filter { it.trashedAt == null && it.versionOf == null }.map { it.name }.toSet())
        pc1.pull()
        assertTrue(db1.dao().activeFiles(drive.id).first().any { it.name == "b-pc2.bin" })
        step("two PCs saved at the same moment: both renames kept")

        // 7. Index gone AND no PC has it → every file comes back by name (folders can't), byte-identical.
        val data2 = account.dataChannels(refresh = true)[chat] ?: error("no data channel")
        account.call(TdApi.DeleteChat(data2))
        assertTrue(account.findDrives().any { it.chatId == chat }, "drive must still be found by its channel description")
        val db3 = db("pc3")
        val pc3dir = File(work, "pc3").apply { mkdirs() }
        val noIndex = runCatching { DriveService.restore(db3, provider(account, chat), chat.toString(), "telegram", pc3dir) }.exceptionOrNull()
        assertTrue(noIndex is NoManifestException, "expected no index, got $noIndex")
        assertEquals(3, DriveService.recover(db3, provider(account, chat), chat.toString(), "telegram", TITLE, pc3dir))
        val recovered = db3.dao().drives().first().single()
        val files = db3.dao().activeFiles(recovered.id).first().associateBy { it.name }
        // Renames lived only in the lost index: files come back under the name they were uploaded with.
        assertEquals(setOf("a.txt", "b.bin", "from-phone.txt"), files.keys)
        val pc3 = DriveService(db3, provider(account, chat), recovered, pc3dir)
        assertContentEquals(content.getValue("b.bin"), pc3.cached(files.getValue("b.bin")).readBytes())
        assertContentEquals(content.getValue("a.txt"), pc3.cached(files.getValue("a.txt")).readBytes())
        assertNotNull(provider(account, chat).manifestVersion())
        step("total index loss: ${files.size} files recovered by name from the channel, byte-identical, new index written")
    }
}
