package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android SQLite/files/crypto; independent directory, no actual user data or API. */
@RunWith(AndroidJUnit4::class)
class CompanionPersistenceTest {
    private class IsolatedContext(base: Context, val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }
    @Test fun personaScopedMemoriesAndEncryptedRestoreSurviveReopen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "companion-db-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(app, root)
        var store = CompanionStore(context)
        try {
            store.savePersona("测试角色", "由测试写入的占位正文", "本地测试")
            store.savePersona("测试角色", "修订后的测试正文", "本地测试修订")
            val owner = store.saveMemory(CompanionPolicy.OWNER, JSONObject().put("kind", "fact").put("text", "主人测试偏好").put("source", "fixture"))
            store.saveMemory("qq:group:fixture-a", JSONObject().put("kind", "event").put("text", "A群独立事件"))
            store.saveMemory("qq:group:fixture-b", JSONObject().put("kind", "event").put("text", "B群独立事件"))
            store.close(); store = CompanionStore(context)
            assertEquals(2, store.persona().getInt("version"))
            assertEquals("修订后的测试正文", store.persona().getString("text"))
            assertEquals(1, store.list("memory", CompanionPolicy.OWNER).length())
            assertFalse(store.memoriesFor("qq:group:fixture-a").toString().contains("主人测试偏好"))
            assertTrue(runCatching { store.saveMemory("qq:group:fixture-a", JSONObject(owner.toString()).put("text", "非法跨域更改")) }.isFailure)
            val encrypted = CompanionBackupCrypto.encrypt(store.exportData(), "fixture-only-passphrase")
            assertFalse(encrypted.contains("主人测试偏好"))
            assertTrue(runCatching { CompanionBackupCrypto.decrypt(encrypted, "wrong-fixture-passphrase") }.isFailure)
            store.deleteMemory(CompanionPolicy.OWNER, owner.getString("id"))
            assertEquals(0, store.memoryHistory(CompanionPolicy.OWNER, owner.getString("id")).length())
            store.restoreData(CompanionBackupCrypto.decrypt(encrypted, "fixture-only-passphrase"))
            store.close(); store = CompanionStore(context)
            assertEquals(1, store.list("memory", CompanionPolicy.OWNER).length())
            assertEquals(1, store.list("memory", "qq:group:fixture-b").length())
            assertFalse(store.settings().getBoolean("networkEnabled"))
            assertTrue(store.settings().getBoolean("paused"))
            assertFalse(store.exportData().has("settings"))
        } finally { store.close(); root.deleteRecursively() }
    }
    @Test fun unsavedFramesStayTransientAndOnlyExplicitSaveCreatesMedia() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "companion-frame-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(app, root)
        var store = CompanionStore(context)
        try {
            fun frame(id: String) = JSONObject().put("id", id).put("scope", CompanionPolicy.OWNER).put("temporary", true)
                .put("parts", JSONArray().put(JSONObject().put("text", "synthetic-image-evidence")))
            store.put("observation", frame("unsaved"))
            store.put("observation", frame("saved"))
            assertEquals(0, store.exportData().getJSONArray("observation").length())
            assertFalse(File(context.filesDir, "companion-media").exists())
            store.saveTemporaryObservation("saved")
            store.close(); store = CompanionStore(context)
            assertNull(store.get("observation", "unsaved"))
            assertNotNull(store.get("observation", "saved"))
            assertTrue(store.observationParts("saved").toString().contains("synthetic-image-evidence"))
            store.deleteObservation("saved")
            assertFalse(File(context.filesDir, "companion-media/${CompanionPolicy.digest("saved")}.json").exists())
        } finally { store.close(); root.deleteRecursively() }
    }
    @Test fun actualSystemVideoDecoderProducesBoundedFrameEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val file = File(app.cacheDir, "video-test-${UUID.randomUUID()}.mp4")
        var observationId: String? = null
        try {
            instrumentation.context.assets.open("companion-test-pattern.mp4").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val evidence = CompanionDevice(app).sampleLocalVideo(android.net.Uri.fromFile(file), "BV0000000000", 1.0, 3.0, 3)
            observationId = evidence.getString("id")
            assertEquals(3, evidence.getJSONArray("frames").length())
            assertFalse(evidence.getBoolean("analyzed"))
            assertEquals(1.0, evidence.getDouble("rangeStart"), 0.01)
            assertEquals(3.0, evidence.getDouble("rangeEnd"), 0.01)
            val parts = CompanionEngine.get(app).store.observationParts(observationId)
            val images = (0 until parts.length()).map(parts::getJSONObject).filter { it.has("inlineData") }
            assertEquals(3, images.size)
            images.forEach { assertEquals("image/jpeg", it.getJSONObject("inlineData").getString("mimeType")) }
            assertTrue(evidence.getString("coverage").contains("无音轨"))
        } finally {
            observationId?.let { CompanionEngine.get(app).store.deleteObservation(it) }
            file.delete()
        }
    }
}
