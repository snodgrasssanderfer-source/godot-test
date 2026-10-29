package io.deepseekharness.mobile.companion.social

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated content encryption. The optional relay never sees event bodies or keys. */
class SyncCipher(key: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 32) }, "AES")
    fun seal(event: JSONObject): JSONObject {
        val envelope = JSONObject().put("id", hash(event.getString("id"))).put("sources", JSONArray(SocialRules.sourceHashes(event)))
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        cipher.updateAAD(canonical(envelope).toByteArray(Charsets.UTF_8))
        val encrypted = iv + cipher.doFinal(event.toString().toByteArray(Charsets.UTF_8))
        return envelope.put("ciphertext", Base64.getEncoder().encodeToString(encrypted)).put("digest", hash(Base64.getEncoder().encodeToString(encrypted)))
    }
    fun open(envelope: JSONObject): JSONObject {
        val packed = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        require(packed.size in 29..524288 && hash(envelope.getString("ciphertext")) == envelope.getString("digest")) { "同步密文校验失败" }
        val aad = JSONObject().put("id", envelope.getString("id")).put("sources", envelope.getJSONArray("sources"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(canonical(aad).toByteArray(Charsets.UTF_8))
        val event = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        require(hash(event.getString("id")) == envelope.getString("id") && SocialRules.sourceHashes(event) == strings(envelope.getJSONArray("sources"))) { "同步事件来源不匹配" }
        return event
    }
    companion object {
        fun decodeKey(value: String): ByteArray = Base64.getDecoder().decode(value).also { require(it.size == 32) { "同步密钥须为 32 字节随机值的 Base64" } }
    }
}
