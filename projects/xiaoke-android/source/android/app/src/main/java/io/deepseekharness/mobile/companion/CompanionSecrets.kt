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
class CompanionSecrets(context: Context, private val slot: String = "gemini") {
    private val prefs = context.getSharedPreferences("companion-secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun configured() = prefs.contains(slot)
    fun save(value: String) {
        require(value.length in 8..4096 && value.none { it.isWhitespace() || it.code < 32 }) { "密钥格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(slot, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) { "密钥保存失败" }
    }
    fun clear() { check(prefs.edit().remove(slot).commit()) }
    internal fun read(): String {
        val value = prefs.getString(slot, null) ?: error("请先在本机填写连接凭据")
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28) { "密钥存储损坏，请重新填写" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    companion object { private const val ALIAS = "dsh-companion-model-key-v1" }
}

