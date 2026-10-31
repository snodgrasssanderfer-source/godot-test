package io.deepseekharness.mobile.companion.claude

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Checks an OpenPGP detached signature (v4, RSA) over a Claude Code release manifest with Anthropic's release key.
 * The key is pinned here (fingerprint 31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE, from
 * https://downloads.claude.ai/keys/claude-code.asc; the same fingerprint cccodroid pins), so a manifest is trusted
 * only if Anthropic signed it, whatever the network path.
 */
object ClaudeSignature {
    const val FINGERPRINT = "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE"
    private val MODULUS = BigInteger(
        "a76f2b495e48f0798af022bd1a2c4151947f82d27178e0ac4573b810989e8f8d23cc19900377165c6a2c54ef3665f16c" +
        "44a553b4c0b9881fc71fdb318e772b9983561aba0935bc76f66fc58184018240974f801f165eab9e75ea4462d939eae3" +
        "ff802a138c90d4665b841811e7967bae8d2278a2bc447614b2904a0051f25918420b3e931d6e9845b7f4a7f846ff4f13" +
        "398e928f7709eff004a1345ff59995357d64d0abdb5128951a9198f4e2075a0c51254176a40ad4e28fe193eb2a180804" +
        "521f018ef8f0bf3ebeaae2d73e0fb2d18f1481bd4583a054a696a6ff3c9d618fd0ffec8d8e7b85d79e200f58c479e8ab" +
        "f11e5cceceb350749ba36c9752902c21d598a3912777cbc6b25b959851319d45a6fdf4d5306a0ce569b7bf55517a9332" +
        "7449c9f9ab73e2841d98f41c28d022a791893b6bb293efb116bf42a4abe54798eb6a5f76b68615fe6fa6babdef6bcc9d" +
        "6a6581a3fa86effc2dd2c752f352301fa95cbb4490dfdae0283f9b7683cbbf4e44a953184c5448d90644b161984ccb34" +
        "0ceadf0882451f9d2ac83d31e2c5b0d60116786de30e063c723d92b59cfece3ab351dd390a26dc0b31001a6d44420e72" +
        "f43e34b48b00535388edba6043d574942f25aea13bcc963ae942bd67575a03c8eb7438ba9a280d8c716b5df4f757842f" +
        "32d84220ed39ab4e524b7c761b7669220988f8278f50f075a437fe9613e45353", 16)
    private val EXPONENT = BigInteger.valueOf(65537)

    /** True only for a valid signature by the pinned key over exactly [data]. */
    fun verify(data: ByteArray, armoredSignature: String): Boolean = runCatching {
        val packet = signaturePacket(dearmor(armoredSignature))
        require(packet[0].toInt() == 4) { "只认 v4 签名" }
        require(packet[2].toInt() == 1) { "只认 RSA 签名" }
        val algorithm = when (packet[3].toInt()) { 8 -> "SHA256withRSA"; 9 -> "SHA384withRSA"; 10 -> "SHA512withRSA"; else -> error("不支持的摘要算法") }
        val hashedLength = u16(packet, 4)
        val hashed = packet.copyOfRange(0, 6 + hashedLength)
        val unhashedLength = u16(packet, 6 + hashedLength)
        var at = 8 + hashedLength + unhashedLength + 2 // skip the two "left 16 bits" bytes
        val bits = u16(packet, at); at += 2
        val value = packet.copyOfRange(at, at + (bits + 7) / 8)
        // The RSA value left-padded to the modulus length.
        val padded = ByteArray((MODULUS.bitLength() + 7) / 8 - value.size) + value
        val n = hashed.size
        val trailer = byteArrayOf(4, 0xFF.toByte(), (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(MODULUS, EXPONENT))
        Signature.getInstance(algorithm).run { initVerify(key); update(data); update(hashed); update(trailer); verify(padded) }
    }.getOrDefault(false)

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun dearmor(text: String): ByteArray {
        val lines = text.lines().map { it.trim() }
        val start = lines.indexOfFirst { it.startsWith("-----BEGIN PGP SIGNATURE") }
        require(start >= 0) { "不是 PGP 签名" }
        val body = lines.drop(start + 1).takeWhile { !it.startsWith("-----END") }
            .dropWhile { it.contains(':') }.filter { it.isNotEmpty() && !it.startsWith("=") }
        return Base64.getDecoder().decode(body.joinToString(""))
    }

    /** The body of the first packet, which must be a signature (tag 2), old or new packet format. */
    private fun signaturePacket(b: ByteArray): ByteArray {
        val header = b[0].toInt() and 0xFF
        require(header and 0x80 != 0) { "不是 OpenPGP 数据" }
        val (tag, length, offset) = if (header and 0x40 != 0) {
            val first = b[1].toInt() and 0xFF
            when {
                first < 192 -> Triple(header and 0x3F, first, 2)
                first < 224 -> Triple(header and 0x3F, ((first - 192) shl 8) + (b[2].toInt() and 0xFF) + 192, 3)
                first == 255 -> Triple(header and 0x3F, (u16(b, 2) shl 16) or u16(b, 4), 6)
                else -> error("不支持分段长度")
            }
        } else when (header and 3) {
            0 -> Triple((header shr 2) and 0xF, b[1].toInt() and 0xFF, 2)
            1 -> Triple((header shr 2) and 0xF, u16(b, 1), 3)
            2 -> Triple((header shr 2) and 0xF, (u16(b, 1) shl 16) or u16(b, 3), 5)
            else -> error("不支持不定长度")
        }
        require(tag == 2) { "第一个包不是签名" }
        return b.copyOfRange(offset, offset + length)
    }
}
