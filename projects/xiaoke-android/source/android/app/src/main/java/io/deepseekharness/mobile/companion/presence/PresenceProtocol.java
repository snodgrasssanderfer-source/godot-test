/* SPDX-License-Identifier: MIT
 * Input/output types and proactive flags adapted from Open-LLM-VTuber
 * 992309c0aa19845960228f880013d4685fde93b5. Copyright (c) 2025 Yi-Ting Chiu.
 * See Open-LLM-VTuber-MIT.txt. Changes: Java 8/Kotlin interop, typed epochs,
 * scoped samples, host-owned media references. No frontend code is copied.
 */
package io.deepseekharness.mobile.companion.presence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PresenceProtocol {
    private PresenceProtocol() {}
    public static final String OWNER_SCOPE = "owner:local";
    public enum Origin { TEXT, SPEECH, PROACTIVE, OBSERVATION }
    public enum Phase { STOPPED, PAUSED, IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

    public static final class TurnToken {
        public final String sessionId;
        public final long epoch, sequence;
        public TurnToken(String sessionId, long epoch, long sequence) {
            this.sessionId = Objects.requireNonNull(sessionId); this.epoch = epoch; this.sequence = sequence;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof TurnToken)) return false;
            TurnToken t = (TurnToken) other;
            return epoch == t.epoch && sequence == t.sequence && sessionId.equals(t.sessionId);
        }
        @Override public int hashCode() { return Objects.hash(sessionId, epoch, sequence); }
        @Override public String toString() { return sessionId + ":" + epoch + ":" + sequence; }
    }
    public static final class InputTicket {
        public final String sessionId;
        public final long epoch;
        public InputTicket(String sessionId, long epoch) { this.sessionId = Objects.requireNonNull(sessionId); this.epoch = epoch; }
        @Override public boolean equals(Object other) {
            return other instanceof InputTicket && epoch == ((InputTicket) other).epoch && sessionId.equals(((InputTicket) other).sessionId);
        }
        @Override public int hashCode() { return Objects.hash(sessionId, epoch); }
    }
    public static final class CaptureTicket {
        public final InputTicket input;
        public final long sequence;
        public CaptureTicket(InputTicket input, long sequence) { this.input = Objects.requireNonNull(input); this.sequence = sequence; }
        @Override public boolean equals(Object other) {
            return other instanceof CaptureTicket && sequence == ((CaptureTicket) other).sequence && input.equals(((CaptureTicket) other).input);
        }
        @Override public int hashCode() { return Objects.hash(input, sequence); }
    }
    /** Explicit immutable opt-in. Pass empty allowedPackages to disable all screen samples. */
    public static final class Policy {
        public final boolean microphone, screen, proactive, reactToChanges;
        public final Set<String> allowedPackages;
        public final long captureIntervalMs, observationMaxAgeMs, idleSpeakMs, quietAfterUserMs, proactiveCooldownMs, operationTimeoutMs;
        public final int maxProactivePerMinute, maxSegments;
        public Policy(boolean microphone, boolean screen, Set<String> allowedPackages, boolean proactive, boolean reactToChanges,
                      long captureIntervalMs, long observationMaxAgeMs, long idleSpeakMs, long quietAfterUserMs,
                      long proactiveCooldownMs, int maxProactivePerMinute, long operationTimeoutMs, int maxSegments) {
            if (captureIntervalMs < 500 || observationMaxAgeMs < 1_000 || observationMaxAgeMs > 60_000 || idleSpeakMs < 1_000 ||
                quietAfterUserMs < 0 || proactiveCooldownMs < 1_000 || maxProactivePerMinute < 1 || maxProactivePerMinute > 10 ||
                operationTimeoutMs < 1_000 || operationTimeoutMs > 300_000 || maxSegments < 1 || maxSegments > 64) throw new IllegalArgumentException("Invalid presence policy");
            for (String p : allowedPackages) if (p == null || p.isEmpty() || p.length() > 200) throw new IllegalArgumentException("Invalid package");
            this.microphone = microphone; this.screen = screen; this.proactive = proactive; this.reactToChanges = reactToChanges;
            this.allowedPackages = Collections.unmodifiableSet(new HashSet<>(allowedPackages));
            this.captureIntervalMs = captureIntervalMs; this.observationMaxAgeMs = observationMaxAgeMs; this.idleSpeakMs = idleSpeakMs;
            this.quietAfterUserMs = quietAfterUserMs; this.proactiveCooldownMs = proactiveCooldownMs;
            this.maxProactivePerMinute = maxProactivePerMinute; this.operationTimeoutMs = operationTimeoutMs; this.maxSegments = maxSegments;
        }
        public static Policy disabled() { return new Policy(false, false, Collections.emptySet(), false, false, 3_000, 8_000, 30_000, 2_000, 20_000, 2, 60_000, 32); }
    }
    /** One authorized sample, not proof of continuous video/audio coverage. */
    public static final class Observation {
        public final String id, scope, packageName, text, imageRef, imageMime;
        public final long capturedAtMs;
        public final boolean changed;
        public Observation(String id, String scope, String packageName, long capturedAtMs, String text, String imageRef, String imageMime, boolean changed) {
            this.id = Objects.requireNonNull(id); this.scope = Objects.requireNonNull(scope); this.packageName = Objects.requireNonNull(packageName);
            this.capturedAtMs = capturedAtMs; this.text = Objects.requireNonNull(text); this.imageRef = imageRef;
            this.imageMime = Objects.requireNonNull(imageMime); this.changed = changed;
        }
    }
    public static final class BatchInput {
        public final String text, scope = OWNER_SCOPE;
        public final Origin origin;
        public final Observation observation;
        public final boolean proactiveSpeak, skipInputMemory, skipInputHistory;
        public BatchInput(String text, Origin origin, Observation observation) {
            this.text = text; this.origin = origin; this.observation = observation;
            proactiveSpeak = origin == Origin.PROACTIVE || origin == Origin.OBSERVATION;
            // As upstream: suppress synthetic INPUT, not all assistant output.
            skipInputMemory = proactiveSpeak; skipInputHistory = proactiveSpeak;
        }
    }
    public static final class Sentence {
        public final String displayText, ttsText;
        public final List<String> expressions;
        public Sentence(String displayText) { this(displayText, displayText, Collections.emptyList()); }
        public Sentence(String displayText, String ttsText, List<String> expressions) {
            this.displayText = Objects.requireNonNull(displayText); this.ttsText = Objects.requireNonNull(ttsText);
            List<String> safe = new ArrayList<>();
            for (String e : expressions) { if (safe.size() == 4) break; safe.add(e.substring(0, Math.min(80, e.length()))); }
            this.expressions = Collections.unmodifiableList(safe);
        }
    }
    public static final class Audio {
        public final String audioRef;
        public final Sentence sentence;
        public final List<Float> volumes;
        public final int sliceLengthMs;
        public final boolean degraded;
        public Audio(String audioRef, Sentence sentence) { this(audioRef, sentence, Collections.emptyList(), 20, false); }
        public Audio(String audioRef, Sentence sentence, List<Float> volumes, int sliceLengthMs, boolean degraded) {
            this.audioRef = audioRef; this.sentence = Objects.requireNonNull(sentence);
            List<Float> safe = new ArrayList<>();
            for (Float v : volumes) {
                if (safe.size() == 30_000) break;
                safe.add(v == null || v.isNaN() || v.isInfinite() ? 0f : Math.max(0f, Math.min(1f, v)));
            }
            this.volumes = Collections.unmodifiableList(safe); this.sliceLengthMs = Math.max(1, Math.min(1_000, sliceLengthMs)); this.degraded = degraded;
        }
        public Audio withSentence(Sentence original) { return new Audio(audioRef, original, volumes, sliceLengthMs, degraded); }
    }
    public static final class Snapshot {
        public final Phase phase;
        public final InputTicket inputTicket;
        public final TurnToken turn;
        public final String observationId, lastReason;
        public Snapshot(Phase phase, InputTicket inputTicket, TurnToken turn, String observationId, String lastReason) {
            this.phase = phase; this.inputTicket = inputTicket; this.turn = turn; this.observationId = observationId; this.lastReason = lastReason;
        }
    }

    public interface Event {}
    public static final class Start implements Event { public final String sessionId; public final Policy policy; public Start(String s, Policy p) { sessionId = s; policy = p; } }
    public enum Control implements Event { STOP, PAUSE, RESUME, INPUT_ACTIVITY, INTERRUPT }
    public static final class PolicyChanged implements Event { public final Policy policy; public PolicyChanged(Policy p) { policy = p; } }
    public static final class Tick implements Event { public final boolean modelAvailable, audioFocus; public Tick(boolean m, boolean a) { modelAvailable = m; audioFocus = a; } }
    public static final class Captured implements Event { public final CaptureTicket ticket; public final Observation sample; public Captured(CaptureTicket t, Observation s) { ticket = t; sample = s; } }
    public static final class CaptureFailed implements Event { public final CaptureTicket ticket; public CaptureFailed(CaptureTicket t) { ticket = t; } }
    public static final class SpeechStarted implements Event { public final InputTicket ticket; public final String utteranceId; public SpeechStarted(InputTicket t, String u) { ticket = t; utteranceId = u; } }
    /** System SpeechRecognizer callback: retains original session ticket and unique utterance ID. */
    public static final class RecognizedSpeech implements Event { public final InputTicket ticket; public final String utteranceId, text; public RecognizedSpeech(InputTicket t, String u, String s) { ticket = t; utteranceId = u; text = s; } }
    public static final class SpeechAudioEnded implements Event { public final TurnToken turn; public final String utteranceId, audioRef; public SpeechAudioEnded(TurnToken t, String u, String a) { turn = t; utteranceId = u; audioRef = a; } }
    public static final class Transcript implements Event { public final TurnToken turn; public final String utteranceId, text; public Transcript(TurnToken t, String u, String s) { turn = t; utteranceId = u; text = s; } }
    public static final class UserText implements Event { public final String text; public UserText(String t) { text = t; } }
    public static final class GeneratedSentence implements Event { public final TurnToken turn; public final Sentence sentence; public GeneratedSentence(TurnToken t, Sentence s) { turn = t; sentence = s; } }
    public static final class GenerationComplete implements Event { public final TurnToken turn; public GenerationComplete(TurnToken t) { turn = t; } }
    public static final class Synthesized implements Event { public final TurnToken turn; public final int sequence; public final Audio audio; public Synthesized(TurnToken t, int s, Audio a) { turn = t; sequence = s; audio = a; } }
    public static final class SynthesisFailed implements Event { public final TurnToken turn; public final int sequence; public SynthesisFailed(TurnToken t, int s) { turn = t; sequence = s; } }
    public static final class PlaybackStarted implements Event { public final TurnToken turn; public final int sequence; public PlaybackStarted(TurnToken t, int s) { turn = t; sequence = s; } }
    public static final class PlaybackProgress implements Event { public final TurnToken turn; public final int sequence, displayChars; public PlaybackProgress(TurnToken t, int s, int c) { turn = t; sequence = s; displayChars = c; } }
    public static final class PlaybackEnded implements Event { public final TurnToken turn; public final int sequence; public PlaybackEnded(TurnToken t, int s) { turn = t; sequence = s; } }
    public static final class Failed implements Event { public final TurnToken turn; public final String reason; public Failed(TurnToken t, String r) { turn = t; reason = r; } }

    public interface Effect {}
    public static final class Listen implements Effect { public final InputTicket ticket; public Listen(InputTicket t) { ticket = t; } }
    public static final class StopListening implements Effect {}
    public static final class Capture implements Effect { public final CaptureTicket ticket; public Capture(CaptureTicket t) { ticket = t; } }
    public static final class CancelCapture implements Effect { public final CaptureTicket ticket; public CancelCapture(CaptureTicket t) { ticket = t; } }
    public static final class Generate implements Effect { public final TurnToken turn; public final BatchInput input; public Generate(TurnToken t, BatchInput i) { turn = t; input = i; } }
    public static final class Transcribe implements Effect { public final TurnToken turn; public final String utteranceId, audioRef; public Transcribe(TurnToken t, String u, String a) { turn = t; utteranceId = u; audioRef = a; } }
    public static final class Synthesize implements Effect { public final TurnToken turn; public final int sequence; public final Sentence sentence; public Synthesize(TurnToken t, int s, Sentence a) { turn = t; sequence = s; sentence = a; } }
    public static final class Present implements Effect { public final TurnToken turn; public final int sequence; public final Audio audio; public Present(TurnToken t, int s, Audio a) { turn = t; sequence = s; audio = a; } }
    public static final class CancelWork implements Effect { public final TurnToken turn; public final String reason; public CancelWork(TurnToken t, String r) { turn = t; reason = r; } }
    public static final class StopPlayback implements Effect { public final TurnToken turn; public StopPlayback(TurnToken t) { turn = t; } }
    public static final class ResetPresentation implements Effect { public final TurnToken turn; public ResetPresentation(TurnToken t) { turn = t; } }
    public static final class ReleaseAudio implements Effect { public final String audioRef; public ReleaseAudio(String r) { audioRef = r; } }
    public static final class DiscardObservation implements Effect { public final String observationId; public DiscardObservation(String id) { observationId = id; } }
    public static final class Finished implements Effect {
        public final TurnToken turn; public final Origin origin; public final String deliveredText, spokenText, reason; public final boolean interrupted;
        public Finished(TurnToken t, Origin o, String d, String s, boolean i, String r) { turn = t; origin = o; deliveredText = d; spokenText = s; interrupted = i; reason = r; }
    }
}
