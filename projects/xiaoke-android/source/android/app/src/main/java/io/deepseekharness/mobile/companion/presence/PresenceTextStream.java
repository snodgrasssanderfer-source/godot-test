/* SPDX-License-Identifier: MIT */
package io.deepseekharness.mobile.companion.presence;

import java.util.ArrayList;
import java.util.List;
import io.deepseekharness.mobile.companion.presence.PresenceProtocol.Sentence;

/** Accepts cumulative visible Gemini text; emits each stable sentence exactly once. */
public final class PresenceTextStream {
    private String emitted = "";
    private boolean finished;
    public List<Sentence> accept(String full, boolean complete) {
        List<Sentence> out = new ArrayList<>();
        if (finished) return out;
        String text = full.substring(0, Math.min(full.length(), 4_000));
        if (!text.startsWith(emitted)) throw new IllegalArgumentException("Model changed emitted prefix");
        if (complete && emitted.isEmpty() && text.trim().equals("[[SILENT]]")) { finished = true; return out; }
        int at = emitted.length();
        while (at < text.length()) {
            int limit = Math.min(text.length(), at + 240), end = -1;
            for (int i = at; i < limit; i++) if ("。！？!?\n".indexOf(text.charAt(i)) >= 0) { end = i + 1; break; }
            if (end < 0 && (complete || limit < text.length())) end = limit;
            if (end < 0) break;
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            if (end <= at) break;
            String chunk = text.substring(at, end); at = end; emitted = text.substring(0, at);
            if (!chunk.trim().isEmpty()) out.add(new Sentence(chunk));
        }
        finished = complete;
        return out;
    }
}
