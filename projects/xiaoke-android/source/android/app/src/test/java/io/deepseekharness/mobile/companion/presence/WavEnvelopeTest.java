package io.deepseekharness.mobile.companion.presence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class WavEnvelopeTest {
    private static byte[] wave(short amplitude) {
        ByteBuffer b = ByteBuffer.allocate(44 + 640).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 640).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(640);
        for (int i = 0; i < 160; i++) b.putShort(amplitude);
        for (int i = 0; i < 160; i++) b.putShort((short) (amplitude / 2));
        return b.array();
    }
    public static void main(String[] args) {
        List<Float> loud = WavEnvelope.read(wave((short) 2000), 20);
        if (loud.size() != 2 || loud.get(0) != 1f || loud.get(1) != 0.5f) throw new AssertionError("RMS/clock slices mismatch");
        List<Float> silence = WavEnvelope.read(wave((short) 0), 20);
        if (silence.size() != 2 || silence.get(0) != 0 || silence.get(1) != 0) throw new AssertionError("silent WAV rejected");
        byte[] malformed = wave((short) 1); malformed[40] = -1; malformed[41] = -1; malformed[42] = -1; malformed[43] = 127;
        if (!WavEnvelope.read(malformed, 20).isEmpty() || !WavEnvelope.read(new byte[0], 20).isEmpty()) throw new AssertionError("invalid WAV accepted");
        System.out.println("PASS PCM16 WAV envelope / real silence / malformed length");
        System.out.println("RESULT 3 tests passed");
    }
}
