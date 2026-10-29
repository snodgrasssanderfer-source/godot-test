/* SPDX-License-Identifier: MIT
 * Normalized per-chunk RMS ported from Open-LLM-VTuber utils/stream_audio.py
 * at 992309c0aa19845960228f880013d4685fde93b5 (Copyright 2025 Yi-Ting Chiu).
 * New bounded PCM16 WAV reader; no pydub/codecs; unsupported formats return no envelope.
 */
package io.deepseekharness.mobile.companion.presence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WavEnvelope {
    private WavEnvelope() {}
    public static List<Float> read(byte[] wav, int sliceMs) {
        if (wav.length < 44 || wav.length > 8 * 1024 * 1024 || sliceMs < 1 || sliceMs > 1000 ||
            !tag(wav, 0).equals("RIFF") || !tag(wav, 8).equals("WAVE")) return Collections.emptyList();
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 0, rate = 0, offset = 12, pcm = -1, bytes = 0;
        while (offset <= wav.length - 8) {
            String name = tag(wav, offset); int size = b.getInt(offset + 4);
            if (size < 0 || size > wav.length - offset - 8) return Collections.emptyList();
            int data = offset + 8;
            if (name.equals("fmt ")) {
                if (size < 16 || b.getShort(data) != 1 || b.getShort(data + 14) != 16) return Collections.emptyList();
                channels = b.getShort(data + 2); rate = b.getInt(data + 4);
                if (channels < 1 || channels > 2 || rate < 8000 || rate > 96000) return Collections.emptyList();
            } else if (name.equals("data")) { pcm = data; bytes = size; }
            offset = data + size + (size & 1);
        }
        if (pcm < 0 || channels == 0) return Collections.emptyList();
        int chunk = Math.max(1, rate * sliceMs / 1000) * channels * 2;
        List<Float> rms = new ArrayList<>(); float peak = 0f;
        for (int start = pcm; start < pcm + bytes && rms.size() < 30_000; start += chunk) {
            int end = Math.min(pcm + bytes, start + chunk); double sum = 0; int n = 0;
            for (int pos = start; pos + 1 < end; pos += 2) { double v = b.getShort(pos); sum += v * v; n++; }
            float value = n == 0 ? 0f : (float) Math.sqrt(sum / n); rms.add(value); peak = Math.max(peak, value);
        }
        if (peak > 0f) for (int i = 0; i < rms.size(); i++) rms.set(i, rms.get(i) / peak);
        return rms;
    }
    private static String tag(byte[] bytes, int at) { return new String(bytes, at, 4, StandardCharsets.US_ASCII); }
}
