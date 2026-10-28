package io.deepseekharness.mobile.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** No credential accessor is exposed to the WebView, model tools, backups or logs. */
class CompanionSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("companion-secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun configured() = prefs.contains("gemini")
    fun save(value: String) {
        require(value.length in 8..4096 && value.none { it.isWhitespace() || it.code < 32 }) { "密钥格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("gemini", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) { "密钥保存失败" }
    }
    fun clear() { check(prefs.edit().remove("gemini").commit()) }
    internal fun read(): String {
        val value = prefs.getString("gemini", null) ?: error("请先在本机填写模型密钥")
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28) { "密钥存储损坏，请重新填写" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    companion object { private const val ALIAS = "dsh-companion-model-key-v1" }
}

object CompanionBackupCrypto {
    private const val ITERATIONS = 210000
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        require(password.length in 8..256) { "备份口令须为 8–256 个字符" }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(data: JSONObject, password: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt)) }
        val body = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", "dsh-companion-encrypted").put("version", 1).put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(cipher.iv)).put("data", b64(body)).toString()
    }
    fun decrypt(text: String, password: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "备份超过 16 MB 限制" }
        val value = JSONObject(text)
        require(value.getString("format") == "dsh-companion-encrypted" && value.getInt("version") == 1 && value.getInt("iterations") == ITERATIONS)
        val salt = Base64.decode(value.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(value.getString("iv"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12)
        return try {
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
                doFinal(Base64.decode(value.getString("data"), Base64.NO_WRAP))
            }
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: javax.crypto.AEADBadTagException) { error("备份口令错误或文件已损坏") }
    }
    private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
}
