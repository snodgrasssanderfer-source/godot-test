/* SPDX-License-Identifier: MIT
 * Ordered delivery/interrupt concepts ported from MIT backend files:
 * conversations/{tts_manager,conversation_handler,conversation_utils}.py at
 * Open-LLM-VTuber 992309c0aa19845960228f880013d4685fde93b5.
 * Copyright (c) 2025 Yi-Ting Chiu. See Open-LLM-VTuber-MIT.txt.
 * New: deterministic event reducer, typed epochs, policy gates, bounded queues,
 * proactive quotas, timeouts and acknowledged-delivery history.
 */
package io.deepseekharness.mobile.companion.presence;

import static io.deepseekharness.mobile.companion.presence.PresenceProtocol.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One serialized caller. No Android imports, I/O, timers or model clients. */
public final class PresenceCore {
    private static final Pattern TTS_PUNCTUATION = Pattern.compile("[\\s.,!?，。！？'\"』」）】]+");
    private static final class Turn {
        final TurnToken token;
        final Origin origin;
        String utterance;
        boolean submitted, sealed, started;
        int nextAllocated, nextPlaying, playedChars;
        Audio playing;
        long deadline;
        final Map<Integer, Sentence> pending = new LinkedHashMap<>();
        final Map<Integer, Audio> ready = new LinkedHashMap<>();
        final StringBuilder delivered = new StringBuilder(), spoken = new StringBuilder();
        Turn(TurnToken token, Origin origin, long deadline) { this.token = token; this.origin = origin; this.deadline = deadline; }
    }
    private Phase phase = Phase.STOPPED;
    private String sessionId, reason, reactedObservation;
    private long epoch, turnSequence, captureSequence, now, nextCaptureAt, captureDeadline, idleSince, lastUserAt;
    private Long lastProactiveAt;
    private Policy policy = Policy.disabled();
    private Turn active;
    private Observation latest;
    private CaptureTicket capture;
    private final ArrayDeque<Long> proactiveStarts = new ArrayDeque<>();

    public Snapshot snapshot() { return new Snapshot(phase, inputTicket(), active == null ? null : active.token, latest == null ? null : latest.id, reason); }
    private boolean running() { return phase != Phase.STOPPED && phase != Phase.PAUSED; }
    private InputTicket inputTicket() { return sessionId == null ? null : new InputTicket(sessionId, epoch); }
    private Turn current(TurnToken t) { return running() && active != null && active.token.equals(t) ? active : null; }

    /** elapsedRealtimeMs must come from a monotonic clock; reverse clock samples are clamped. */
    public List<Effect> accept(Event event, long elapsedRealtimeMs) {
        Objects.requireNonNull(event);
        if (elapsedRealtimeMs < 0) throw new IllegalArgumentException("Negative elapsed time");
        now = Math.max(now, elapsedRealtimeMs);
        List<Effect> out = new ArrayList<>();
        if (event instanceof Start) {
            Start e = (Start) event;
            if (e.sessionId == null || e.sessionId.trim().isEmpty() || e.sessionId.length() > 128) throw new IllegalArgumentException("Invalid session ID");
            Objects.requireNonNull(e.policy);
            reset("session-replaced", out);
            sessionId = e.sessionId; policy = Objects.requireNonNull(e.policy);
            proactiveStarts.clear(); lastProactiveAt = null;
            phase = Phase.IDLE; reason = null; idleSince = now; lastUserAt = now; nextCaptureAt = now;
            if (policy.microphone) out.add(new Listen(inputTicket()));
        } else if (event == Control.STOP) {
            reset("stopped", out); phase = Phase.STOPPED; sessionId = null;
        } else if (event == Control.PAUSE) {
            if (running()) { reset("paused", out); phase = Phase.PAUSED; }
        } else if (event == Control.RESUME) {
            if (phase == Phase.PAUSED) {
                epoch++; phase = Phase.IDLE; reason = null; idleSince = now; lastUserAt = now; nextCaptureAt = now;
                if (policy.microphone) out.add(new Listen(inputTicket()));
            }
        } else if (event instanceof PolicyChanged) {
            Objects.requireNonNull(((PolicyChanged) event).policy);
            boolean wasRunning = running(); Phase previous = phase;
            reset("perception-policy-changed", out);
            policy = Objects.requireNonNull(((PolicyChanged) event).policy);
            phase = wasRunning ? Phase.IDLE : previous;
            idleSince = now; lastUserAt = now; nextCaptureAt = now;
            if (wasRunning && policy.microphone) out.add(new Listen(inputTicket()));
        } else if (event instanceof Tick) {
            tick((Tick) event, out);
        } else if (event == Control.INPUT_ACTIVITY) {
            if (running()) { lastUserAt = now; idleSince = now; }
        } else if (event instanceof Captured) {
            captured((Captured) event, out);
        } else if (event instanceof CaptureFailed) {
            if (Objects.equals(((CaptureFailed) event).ticket, capture)) capture = null;
        } else if (event instanceof SpeechStarted) {
            SpeechStarted e = (SpeechStarted) event;
            if (running() && policy.microphone && Objects.equals(e.ticket, inputTicket()) && e.utteranceId != null && !e.utteranceId.isEmpty()) {
                if (active == null || !e.utteranceId.equals(active.utterance)) {
                    cancel("user-speech", out);
                    Turn t = newTurn(Origin.SPEECH); t.utterance = e.utteranceId;
                    phase = Phase.LISTENING; lastUserAt = now;
                }
            }
        } else if (event instanceof RecognizedSpeech) {
            RecognizedSpeech e = (RecognizedSpeech) event;
            if (active != null && Objects.equals(e.ticket, inputTicket()) && Objects.equals(active.utterance, e.utteranceId)) {
                // Preserve the original callback identity; do not let adapters relabel old STT with a new token.
                transcript(new Transcript(active.token, e.utteranceId, e.text), out);
            }
        } else if (event instanceof SpeechAudioEnded) {
            SpeechAudioEnded e = (SpeechAudioEnded) event; Turn t = current(e.turn);
            if (t != null && Objects.equals(t.utterance, e.utteranceId) && !t.submitted && phase == Phase.LISTENING) {
                phase = Phase.TRANSCRIBING; t.deadline = now + policy.operationTimeoutMs;
                out.add(new Transcribe(t.token, e.utteranceId, e.audioRef));
            } else if (e.audioRef != null) out.add(new ReleaseAudio(e.audioRef));
        } else if (event instanceof Transcript) {
            transcript((Transcript) event, out);
        } else if (event instanceof UserText) {
            String text = ((UserText) event).text;
            if (running() && !text.trim().isEmpty()) {
                cancel("new-user-text", out); lastUserAt = now;
                generate(newTurn(Origin.TEXT), bounded(text.trim(), 8_000), out);
            }
        } else if (event instanceof GeneratedSentence) {
            sentence((GeneratedSentence) event, out);
        } else if (event instanceof GenerationComplete) {
            Turn t = current(((GenerationComplete) event).turn);
            if (t != null && t.submitted) { t.sealed = true; pump(t, out); }
        } else if (event instanceof Synthesized) {
            synthesized((Synthesized) event, out);
        } else if (event instanceof SynthesisFailed) {
            SynthesisFailed e = (SynthesisFailed) event; Turn t = current(e.turn);
            Sentence s = t == null ? null : t.pending.remove(e.sequence);
            if (s != null) { t.ready.put(e.sequence, new Audio(null, s, java.util.Collections.emptyList(), 20, true)); pump(t, out); }
        } else if (event instanceof PlaybackStarted) {
            PlaybackStarted e = (PlaybackStarted) event; Turn t = current(e.turn);
            if (t != null && t.playing != null && t.nextPlaying == e.sequence) {
                t.started = true; phase = Phase.SPEAKING; t.deadline = now + policy.operationTimeoutMs;
            }
        } else if (event instanceof PlaybackProgress) {
            PlaybackProgress e = (PlaybackProgress) event; Turn t = current(e.turn);
            if (t != null && t.playing != null && t.started && t.nextPlaying == e.sequence) {
                t.playedChars = Math.max(t.playedChars, Math.max(0, Math.min(e.displayChars, t.playing.sentence.displayText.length())));
                t.deadline = now + policy.operationTimeoutMs;
            }
        } else if (event instanceof PlaybackEnded) {
            PlaybackEnded e = (PlaybackEnded) event; Turn t = current(e.turn);
            if (t != null && t.playing != null && t.started && t.nextPlaying == e.sequence) {
                t.delivered.append(t.playing.sentence.displayText);
                if (t.playing.audioRef != null) { t.spoken.append(t.playing.sentence.displayText); out.add(new ReleaseAudio(t.playing.audioRef)); }
                t.playing = null; t.started = false; t.playedChars = 0; t.nextPlaying++;
                t.deadline = now + policy.operationTimeoutMs; pump(t, out);
            }
        } else if (event instanceof Failed) {
            Failed e = (Failed) event;
            if (current(e.turn) != null) cancel(bounded(e.reason, 100), out);
        } else if (event == Control.INTERRUPT) {
            if (running()) { cancel("user-interrupt", out); lastUserAt = now; }
        } else throw new IllegalArgumentException("Unsupported event");
        return out;
    }

    private void captured(Captured e, List<Effect> out) {
        Observation s = e.sample;
        boolean allowed = running() && policy.screen && e.ticket != null && e.ticket.equals(capture) &&
            Objects.equals(e.ticket.input, inputTicket()) && OWNER_SCOPE.equals(s.scope) && policy.allowedPackages.contains(s.packageName) &&
            s.capturedAtMs >= now - policy.observationMaxAgeMs && s.capturedAtMs <= now &&
            !s.id.isEmpty() && s.id.length() <= 128 && s.text.length() <= 16_000 &&
            ("image/jpeg".equals(s.imageMime) || "image/png".equals(s.imageMime));
        if (Objects.equals(e.ticket, capture)) capture = null;
        if (allowed) {
            if (latest != null && !latest.id.equals(s.id)) out.add(new DiscardObservation(latest.id));
            latest = s;
        } else if (latest == null || !latest.id.equals(s.id)) out.add(new DiscardObservation(s.id));
    }
    private void transcript(Transcript e, List<Effect> out) {
        Turn t = current(e.turn);
        if (t != null && Objects.equals(t.utterance, e.utteranceId) && !t.submitted && (phase == Phase.LISTENING || phase == Phase.TRANSCRIBING)) {
            String text = bounded(e.text.trim(), 8_000); lastUserAt = now;
            if (text.isEmpty()) cancel("empty-transcript", out); else generate(t, text, out);
        }
    }
    private void sentence(GeneratedSentence e, List<Effect> out) {
        Turn t = current(e.turn);
        if (t == null || !t.submitted || t.sealed) return;
        if (t.nextAllocated >= policy.maxSegments || e.sentence.displayText.length() > 4_000 || e.sentence.ttsText.length() > 4_000) {
            cancel("output-budget", out); return;
        }
        int sequence = t.nextAllocated++;
        t.pending.put(sequence, e.sentence); t.deadline = now + policy.operationTimeoutMs;
        if (TTS_PUNCTUATION.matcher(e.sentence.ttsText).replaceAll("").isEmpty()) {
            t.ready.put(sequence, new Audio(null, e.sentence)); t.pending.remove(sequence); pump(t, out);
        } else out.add(new Synthesize(t.token, sequence, e.sentence));
    }
    private void synthesized(Synthesized e, List<Effect> out) {
        Turn t = current(e.turn);
        Sentence expected = t == null ? null : t.pending.remove(e.sequence);
        if (expected != null) {
            // TTS responses cannot replace the authoritative model text or expression hints.
            t.ready.put(e.sequence, e.audio.withSentence(expected)); pump(t, out);
        } else if (e.audio.audioRef != null && !isOwnedAudio(e.audio.audioRef)) out.add(new ReleaseAudio(e.audio.audioRef));
    }
    private Turn newTurn(Origin origin) {
        active = new Turn(new TurnToken(sessionId, epoch, ++turnSequence), origin, now + policy.operationTimeoutMs);
        reason = null; return active;
    }
    private Observation freshObservation() {
        return latest != null && policy.screen && policy.allowedPackages.contains(latest.packageName) &&
            latest.capturedAtMs >= now - policy.observationMaxAgeMs && latest.capturedAtMs <= now ? latest : null;
    }
    private void generate(Turn t, String text, List<Effect> out) {
        t.submitted = true; t.deadline = now + policy.operationTimeoutMs; phase = Phase.THINKING;
        out.add(new Generate(t.token, new BatchInput(text, t.origin, freshObservation())));
    }
    private void tick(Tick e, List<Effect> out) {
        if (!running()) return;
        if (capture != null && now >= captureDeadline) { out.add(new CancelCapture(capture)); capture = null; }
        if (latest != null && freshObservation() == null) { out.add(new DiscardObservation(latest.id)); latest = null; }
        if (policy.screen && !policy.allowedPackages.isEmpty() && capture == null && now >= nextCaptureAt) {
            capture = new CaptureTicket(inputTicket(), ++captureSequence);
            captureDeadline = now + policy.operationTimeoutMs; nextCaptureAt = now + policy.captureIntervalMs;
            out.add(new Capture(capture));
        }
        if (active != null && now >= active.deadline) cancel("operation-timeout", out);
        if (active != null || phase != Phase.IDLE || !e.modelAvailable || !e.audioFocus) return;
        if (now - lastUserAt < policy.quietAfterUserMs || (lastProactiveAt != null && now - lastProactiveAt < policy.proactiveCooldownMs)) return;
        while (!proactiveStarts.isEmpty() && now - proactiveStarts.peekFirst() >= 60_000) proactiveStarts.removeFirst();
        if (proactiveStarts.size() >= policy.maxProactivePerMinute) return;
        Observation sample = freshObservation();
        boolean reaction = policy.reactToChanges && sample != null && sample.changed && !sample.id.equals(reactedObservation);
        boolean idle = policy.proactive && now - idleSince >= policy.idleSpeakMs;
        if (!reaction && !idle) return;
        lastProactiveAt = now; proactiveStarts.addLast(now);
        if (reaction) reactedObservation = sample.id;
        Origin origin = reaction ? Origin.OBSERVATION : Origin.PROACTIVE;
        String text = reaction ? "根据当前获准的画面样本自然回应；只描述确有依据的变化。" : "结合当前对话自然说一句；没有值得补充的内容时可以保持安静。";
        generate(newTurn(origin), text, out);
    }
    private void pump(Turn t, List<Effect> out) {
        if (t.playing != null) return;
        Audio next = t.ready.remove(t.nextPlaying);
        if (next != null) {
            t.playing = next; t.deadline = now + policy.operationTimeoutMs;
            out.add(new Present(t.token, t.nextPlaying, next)); return;
        }
        if (t.sealed && t.nextPlaying == t.nextAllocated) {
            out.add(new ResetPresentation(t.token));
            out.add(new Finished(t.token, t.origin, t.delivered.toString(), t.spoken.toString(), false, null));
            active = null; phase = Phase.IDLE; idleSince = now;
        } else phase = Phase.THINKING;
    }
    private boolean isOwnedAudio(String ref) {
        if (active == null) return false;
        if (active.playing != null && ref.equals(active.playing.audioRef)) return true;
        for (Audio a : active.ready.values()) if (ref.equals(a.audioRef)) return true;
        return false;
    }
    private void cancel(String why, List<Effect> out) {
        Turn t = active;
        // Invalidate before side effects: synchronous cancel callbacks cannot revive the turn.
        active = null;
        if (t != null) {
            out.add(new CancelWork(t.token, why)); out.add(new StopPlayback(t.token)); out.add(new ResetPresentation(t.token));
            String partial = t.started && t.playing != null ? t.playing.sentence.displayText.substring(0, t.playedChars) : "";
            t.delivered.append(partial);
            if (t.playing != null && t.playing.audioRef != null) t.spoken.append(partial);
            Set<String> owned = new LinkedHashSet<>();
            for (Audio a : t.ready.values()) if (a.audioRef != null) owned.add(a.audioRef);
            if (t.playing != null && t.playing.audioRef != null) owned.add(t.playing.audioRef);
            for (String ref : owned) out.add(new ReleaseAudio(ref));
            out.add(new Finished(t.token, t.origin, t.delivered.toString(), t.spoken.toString(), true, why));
        }
        if (running()) phase = Phase.IDLE;
        idleSince = now; reason = why;
    }
    private void reset(String why, List<Effect> out) {
        epoch++; cancel(why, out); out.add(new StopListening());
        if (capture != null) out.add(new CancelCapture(capture)); capture = null;
        if (latest != null) out.add(new DiscardObservation(latest.id)); latest = null; reactedObservation = null;
    }
    private static String bounded(String s, int limit) { return s.substring(0, Math.min(s.length(), limit)); }
}
