package io.deepseekharness.mobile.companion.social

import android.content.Context
import android.util.AtomicFile
import io.deepseekharness.mobile.companion.CompanionSecrets
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** App-private, no Android cloud backup, authenticated encryption; secrets stay in Keystore slots. */
internal class SocialVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "companion-social").apply { check(mkdirs() || isDirectory) }
    private val credential = CompanionSecrets(context, "social-local-v1")
    init { if (!credential.configured()) credential.save(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))) }
    private fun key() = SecretKeySpec(SyncCipher.decodeKey(credential.read()), "AES")
    private fun file(name: String): AtomicFile {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(name)); return AtomicFile(File(directory, name + ".bin"))
    }
    @Synchronized fun write(name: String, bytes: ByteArray) {
        require(bytes.size <= 32 * 1024 * 1024)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray()) }
        val atomic = file(name); val output = atomic.startWrite()
        try { output.write(iv + cipher.doFinal(bytes)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    @Synchronized fun read(name: String): ByteArray? {
        val atomic = file(name)
        if (!atomic.baseFile.exists()) return null
        val bytes = atomic.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 32 * 1024 * 1024 + 28); output.write(buffer, 0, count) }
            val result = output.toByteArray()
            require(result.size in 29..(32 * 1024 * 1024 + 28)); result
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(name.toByteArray())
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
    @Synchronized fun delete(name: String) { file(name).delete() }
    fun load() = read("ledger")?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
    fun persist(value: JSONObject) = write("ledger", value.toString().toByteArray(Charsets.UTF_8))
}
