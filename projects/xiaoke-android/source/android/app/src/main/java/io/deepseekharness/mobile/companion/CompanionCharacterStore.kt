package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.File

/** User artwork is local presentation data, never a model input or relationship memory. */
object CompanionCharacterStore {
    @Volatile private var cached: String? = null
    fun file(context: Context) = File(context.filesDir, "companion-character.png")
    @Synchronized fun metadata(context: Context): JSONObject {
        cached?.let { return JSONObject(it) }
        val png = file(context)
        val value = if (!png.isFile) JSONObject() else JSONObject().put("path", png.absolutePath)
            .put("version", png.lastModified()).put("layout", context.getSharedPreferences("companion-character", Context.MODE_PRIVATE).getString("layout", "portrait"))
        cached = value.toString(); return value
    }
    @Synchronized fun save(context: Context, encoded: String, layout: String): JSONObject {
        require(layout in setOf("portrait", "sprite-4x6"))
        require(encoded.length <= 12 * 1024 * 1024) { "角色图片过大" }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 24..8 * 1024 * 1024 && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte)) { "请选择不超过 8 MB 的 PNG" }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096 && dimensions.outWidth.toLong() * dimensions.outHeight <= 8_388_608) { "角色图片尺寸超限" }
        if (layout == "sprite-4x6") require(dimensions.outWidth % 4 == 0 && dimensions.outHeight % 6 == 0) { "图集须为 4 列 × 6 行" }
        val target = file(context); val temp = File(context.filesDir, "companion-character.tmp")
        try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "本机角色图片保存失败" } } finally { temp.delete() }
        context.getSharedPreferences("companion-character", Context.MODE_PRIVATE).edit().putString("layout", layout).apply()
        cached = null; return metadata(context)
    }
    @Synchronized fun clear(context: Context): JSONObject {
        file(context).delete(); context.getSharedPreferences("companion-character", Context.MODE_PRIVATE).edit().clear().apply()
        cached = null; return metadata(context)
    }
}
