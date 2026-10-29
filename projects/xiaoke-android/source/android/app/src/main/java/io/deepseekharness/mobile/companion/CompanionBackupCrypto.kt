package io.deepseekharness.mobile.companion

import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object CompanionBackupCrypto {
    private const val ITERATIONS = 210000
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        require(password.length in 8..256) { "备份口令须为 8–256 个字符" }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(data: JSONObject, password: String): String {
        val plain = data.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 11 * 1024 * 1024) { "备份内容超过当前 11 MB 加密导出上限；未导出不完整备份" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt)) }
        val body = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        return JSONObject().put("format", "dsh-companion-encrypted").put("version", 1).put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(cipher.iv)).put("data", b64(body)).toString()
    }
    fun decrypt(text: String, password: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "备份超过 16 MB 限制" }
        val value = JSONObject(text)
        require(value.getString("format") == "dsh-companion-encrypted" && value.getInt("version") == 1 && value.getInt("iterations") == ITERATIONS)
        val salt = Base64.getDecoder().decode(value.getString("salt"))
        val iv = Base64.getDecoder().decode(value.getString("iv"))
        require(salt.size == 16 && iv.size == 12)
        return try {
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
                doFinal(Base64.getDecoder().decode(value.getString("data")))
            }
            try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } catch (_: javax.crypto.AEADBadTagException) { error("备份口令错误或文件已损坏") }
    }
    private fun b64(data: ByteArray) = Base64.getEncoder().encodeToString(data)
}
