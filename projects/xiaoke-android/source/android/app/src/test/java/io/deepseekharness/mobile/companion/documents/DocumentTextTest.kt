package io.deepseekharness.mobile.companion.documents

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun zipReadsRealTextAndLabelsUnobservedBinary() {
        val result = DocumentText.archive(ByteArrayInputStream(zip("note.txt" to "记录".toByteArray(), "photo.png" to byteArrayOf(1,2,3))))
        assertTrue(result.getString("text").contains("记录"))
        assertFalse(result.getJSONArray("entries").getJSONObject(1).getBoolean("read"))
    }
    @Test fun traversalAndExpansionLimitStopParsing() {
        assertThrows(IllegalArgumentException::class.java) { DocumentText.archive(ByteArrayInputStream(zip("../secret.txt" to byteArrayOf(1)))) }
        assertThrows(IllegalArgumentException::class.java) { DocumentText.archive(ByteArrayInputStream(zip("large.txt" to ByteArray(DocumentText.MAX_BYTES + 1)))) }
    }
    @Test fun malformedEncodingIsNotInventedText() {
        assertThrows(IllegalStateException::class.java) { DocumentText.decode(byteArrayOf(0xff.toByte(),0xff.toByte())) }
    }
}
