package io.deepseekharness.mobile.companion.memory

/**
 * BERT tokenization as bge-small-zh-v1.5's tokenizer.json configures it: BertNormalizer (clean text, space out
 * CJK characters, no lowercasing, no accent stripping), BertPreTokenizer (whitespace and punctuation), then
 * greedy longest-match WordPiece with "##". Checked against the HF `tokenizers` output in the unit tests.
 */
class WordPiece(vocab: List<String>) {
    private val ids = vocab.withIndex().associate { it.value to it.index }
    private val unk = ids.getValue("[UNK]")
    val cls = ids.getValue("[CLS]")
    val sep = ids.getValue("[SEP]")

    fun encode(text: String, maxLength: Int = 256): IntArray {
        val out = ArrayList<Int>()
        out.add(cls)
        for (word in words(text)) {
            if (out.size >= maxLength - 1) break
            out.addAll(piece(word))
        }
        while (out.size > maxLength - 1) out.removeAt(out.size - 1)
        out.add(sep)
        return out.toIntArray()
    }

    private fun piece(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > 100) return listOf(unk)
        val result = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (end > start) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                ids[sub]?.let { found = it }
                if (found != null) break
                end = word.offsetByCodePoints(end, -1)
            }
            if (found == null) return listOf(unk)
            result.add(found!!)
            start = end
        }
        return result
    }

    private fun words(text: String): List<String> {
        val normalized = StringBuilder()
        text.codePoints().forEach { c ->
            when {
                c == 0 || c == 0xFFFD || (Character.isISOControl(c) && c != '\t'.code && c != '\n'.code && c != '\r'.code) -> {}
                Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR.toInt() -> normalized.append(' ')
                cjk(c) -> normalized.append(' ').appendCodePoint(c).append(' ')
                else -> normalized.appendCodePoint(c)
            }
        }
        val out = ArrayList<String>()
        for (token in normalized.split(' ')) {
            if (token.isEmpty()) continue
            val current = StringBuilder()
            token.codePoints().forEach { c ->
                if (punctuation(c)) {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.setLength(0) }
                    out.add(String(Character.toChars(c)))
                } else current.appendCodePoint(c)
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }

    private fun cjk(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F ||
        c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F

    private fun punctuation(c: Int): Boolean {
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }
}
