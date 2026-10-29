package io.deepseekharness.mobile.companion.social

import java.net.URI

object MediaFormats {
    fun remoteUrl(raw: String): String? = runCatching {
        val uri = URI(raw); val host = uri.host?.lowercase().orEmpty()
        val approved = host == "qpic.cn" || host.endsWith(".qpic.cn") || host == "qlogo.cn" || host.endsWith(".qlogo.cn") || host in setOf("multimedia.nt.qq.com", "grouptalk.c2c.qq.com")
        if (!approved || uri.scheme !in setOf("https", "http") || uri.userInfo != null || uri.port !in setOf(-1,443) || uri.fragment != null) null
        else if (uri.scheme == "http") "https:" + raw.substringAfter(':') else raw
    }.getOrNull()
    fun mime(bytes: ByteArray, kind: String): String? {
        fun ascii(start: Int, end: Int) = if (bytes.size >= end) String(bytes.copyOfRange(start,end), Charsets.US_ASCII) else ""
        if (kind == "record") {
            if (ascii(0,4) == "RIFF" && ascii(8,12) == "WAVE") return "audio/wav"
            if (ascii(0,3) == "ID3" || bytes.size > 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xe0 == 0xe0) return "audio/mpeg"
            if (ascii(4,8) == "ftyp") return "audio/mp4"
        }
        if (kind == "video" && ascii(4,8) == "ftyp") return "video/mp4"
        return null // SILK/AMR and unrecognized data need an explicitly installed decoder, never relabelled.
    }
}
