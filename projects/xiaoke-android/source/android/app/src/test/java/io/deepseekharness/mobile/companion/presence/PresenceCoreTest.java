package io.deepseekharness.mobile.companion.presence;

import static io.deepseekharness.mobile.companion.presence.PresenceProtocol.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Self-written deterministic tests. No JUnit, Android SDK, microphone, model or network. */
public final class PresenceCoreTest {
    private static int passed;
    private static Policy policy(boolean mic, boolean screen, boolean proactive, boolean reaction, int segments) {
        return new Policy(mic, screen, Collections.singleton("game.allowed"), proactive, reaction, 1_000, 4_000, 1_000, 500, 1_000, 2, 5_000, segments);
    }
    private static PresenceCore started(Policy p) { PresenceCore c = new PresenceCore(); c.accept(new Start("session", p), 0); return c; }
    private static List<Effect> send(PresenceCore c, Event e, long at) { return c.accept(e, at); }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static <T> T one(List<Effect> effects, Class<T> type) {
        T found = null;
        for (Effect e : effects) if (type.isInstance(e)) { check(found == null, "duplicate " + type.getSimpleName()); found = type.cast(e); }
        check(found != null, "missing " + type.getSimpleName()); return found;
    }
    private static boolean has(List<Effect> effects, Class<?> type) { for (Effect e : effects) if (type.isInstance(e)) return true; return false; }
    private static TurnToken user(PresenceCore c, String text, long at) { return one(send(c, new UserText(text), at), Generate.class).turn; }
    private static CaptureTicket capture(PresenceCore c, long at) { return one(send(c, new Tick(true, true), at), Capture.class).ticket; }
    private static Observation sample(String id, long at, boolean changed) { return new Observation(id, OWNER_SCOPE, "game.allowed", at, "当前画面", "frame:" + id, "image/jpeg", changed); }
    private static Audio audio(String ref, String text) { return new Audio(ref, new Sentence(text)); }
    private static void sentence(PresenceCore c, TurnToken t, String text, long at) { one(send(c, new GeneratedSentence(t, new Sentence(text)), at), Synthesize.class); }
    private static List<Effect> finishPlayback(PresenceCore c, TurnToken t, int n, long at) {
        send(c, new PlaybackStarted(t, n), at); return send(c, new PlaybackEnded(t, n), at + 1);
    }
    private static void test(String name, Runnable run) { run.run(); passed++; System.out.println("PASS " + name); }

    public static void main(String[] args) {
        test("disabled and paused sessions never request perception or model", () -> {
            PresenceCore c = started(Policy.disabled());
            check(send(c, new Tick(true, true), 9_000).isEmpty(), "disabled work");
            send(c, Control.PAUSE, 9_001);
            check(send(c, new UserText("hello"), 9_002).isEmpty(), "paused input");
            check(send(c, new Tick(true, true), 20_000).isEmpty(), "paused tick");
        });
        test("fresh game-screen change can initiate a turn during the session", () -> {
            PresenceCore c = started(policy(true, true, false, true, 32));
            CaptureTicket cap = capture(c, 0);
            send(c, new Captured(cap, sample("screen-1", 100, true)), 100);
            Generate g = one(send(c, new Tick(true, true), 500), Generate.class);
            check(g.input.origin == Origin.OBSERVATION, "reaction origin");
            check(g.input.observation.id.equals("screen-1") && g.input.proactiveSpeak, "observation context");
            check(g.input.skipInputHistory && g.input.skipInputMemory, "synthetic flags");
            check(!has(send(c, new Tick(true, true), 501), Generate.class), "overlapping generation");
        });
        test("ordinary user text retains history flags and owner scope", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32));
            Generate g = one(send(c, new UserText("你好"), 1), Generate.class);
            check(!g.input.skipInputHistory && !g.input.skipInputMemory && OWNER_SCOPE.equals(g.input.scope), "wrong flags");
        });
        test("cross-domain, forbidden-package, future and expired samples are discarded", () -> {
            Observation[] bad = {
                new Observation("qq", "qq:group:42", "game.allowed", 0, "", null, "image/jpeg", true),
                new Observation("forbidden", OWNER_SCOPE, "bank.forbidden", 0, "", null, "image/jpeg", true),
                sample("future", 100, true), sample("expired", -5_000, true)
            };
            for (Observation s : bad) {
                PresenceCore c = started(policy(false, true, false, true, 32));
                one(send(c, new Captured(capture(c, 0), s), 0), DiscardObservation.class);
                check(c.snapshot().observationId == null, "accepted " + s.id);
            }
        });
        test("pause resume and changed scope fence old capture and microphone callbacks", () -> {
            PresenceCore c = started(policy(true, true, false, false, 32));
            InputTicket oldMic = c.snapshot().inputTicket; CaptureTicket cap = capture(c, 0);
            send(c, Control.PAUSE, 1); send(c, Control.RESUME, 2);
            one(send(c, new Captured(cap, sample("late", 3, true)), 3), DiscardObservation.class);
            send(c, new SpeechStarted(oldMic, "late-mic"), 4);
            check(c.snapshot().turn == null, "old microphone created turn");
            send(c, new PolicyChanged(Policy.disabled()), 5);
            check(c.snapshot().observationId == null && !has(send(c, new Tick(true, true), 6), Capture.class), "scope revoke failed");
        });
        test("confirmed speech cancels model, playback and presentation before STT", () -> {
            PresenceCore c = started(policy(true, false, false, false, 32));
            TurnToken old = user(c, "old", 1); sentence(c, old, "old answer", 2);
            one(send(c, new Synthesized(old, 0, audio("audio-old", "wrong")), 3), Present.class);
            send(c, new PlaybackStarted(old, 0), 4); send(c, new PlaybackProgress(old, 0, 3), 5);
            List<Effect> out = send(c, new SpeechStarted(c.snapshot().inputTicket, "utterance"), 6);
            check(out.get(0) instanceof CancelWork && out.get(1) instanceof StopPlayback && out.get(2) instanceof ResetPresentation, "cancel order");
            Finished f = one(out, Finished.class);
            check(f.interrupted && "old".equals(f.deliveredText) && "old".equals(f.spokenText), "unplayed response persisted");
            TurnToken fresh = c.snapshot().turn;
            one(send(c, new SpeechAudioEnded(fresh, "utterance", "input-audio"), 7), Transcribe.class);
            Generate g = one(send(c, new Transcript(fresh, "utterance", "new words"), 8), Generate.class);
            check(g.turn.equals(fresh) && g.input.origin == Origin.SPEECH, "STT-to-Gemini turn");
            check(send(c, new GeneratedSentence(old, new Sentence("stale model")), 9).isEmpty(), "late model resurrected");
            check(send(c, new PlaybackEnded(old, 0), 10).isEmpty(), "old playback ended new turn");
            one(send(c, new Synthesized(old, 0, audio("late-audio", "stale")), 11), ReleaseAudio.class);
            check(c.snapshot().turn.equals(fresh), "old result replaced new");
        });
        test("old STT transcript cannot replace later utterance", () -> {
            PresenceCore c = started(policy(true, false, false, false, 32));
            InputTicket mic = c.snapshot().inputTicket;
            send(c, new SpeechStarted(mic, "first"), 0); TurnToken first = c.snapshot().turn;
            send(c, new SpeechStarted(mic, "second"), 1); TurnToken second = c.snapshot().turn;
            check(send(c, new Transcript(first, "first", "old"), 2).isEmpty(), "stale transcript accepted");
            check(one(send(c, new Transcript(second, "second", "new"), 3), Generate.class).input.text.equals("new"), "new transcript missing");
            check(send(c, new Transcript(second, "second", "duplicate"), 4).isEmpty(), "duplicate transcript generated");
        });
        test("system STT callback uses original ticket and utterance ID without relabeling", () -> {
            PresenceCore c = started(policy(true, false, false, false, 32)); InputTicket ticket = c.snapshot().inputTicket;
            send(c, new SpeechStarted(ticket, "first"), 0);
            send(c, new SpeechStarted(ticket, "second"), 1);
            check(send(c, new RecognizedSpeech(ticket, "first", "late words"), 2).isEmpty(), "late OS result relabeled");
            check(one(send(c, new RecognizedSpeech(ticket, "second", "current words"), 3), Generate.class).input.text.equals("current words"), "OS STT result missing");
            send(c, Control.PAUSE, 4); send(c, Control.RESUME, 5);
            check(send(c, new RecognizedSpeech(ticket, "second", "late after resume"), 6).isEmpty(), "old OS session reused");
        });
        test("out-of-order TTS stays in sentence order and waits for playback acknowledgements", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0);
            sentence(c, t, "one", 1); sentence(c, t, "two", 2);
            check(!has(send(c, new Synthesized(t, 1, audio("a1", "injected")), 3), Present.class), "second played early");
            Present p = one(send(c, new Synthesized(t, 0, audio("a0", "injected")), 4), Present.class);
            check(p.sequence == 0 && p.audio.sentence.displayText.equals("one"), "TTS overwrote caption");
            check(!has(send(c, new GenerationComplete(t), 5), Finished.class), "generation treated as playback");
            check(send(c, new PlaybackEnded(t, 0), 6).isEmpty(), "unstarted playback acknowledged");
            check(one(finishPlayback(c, t, 0, 7), Present.class).sequence == 1, "second missing");
            Finished f = one(finishPlayback(c, t, 1, 9), Finished.class);
            check(f.deliveredText.equals("onetwo") && f.spokenText.equals("onetwo") && !f.interrupted, "wrong transcript");
        });
        test("TTS failure creates explicit silent slot without blocking later audio", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0);
            sentence(c, t, "silent", 1); sentence(c, t, "spoken", 2);
            send(c, new Synthesized(t, 1, audio("a1", "spoken")), 3); send(c, new GenerationComplete(t), 4);
            Present p = one(send(c, new SynthesisFailed(t, 0), 5), Present.class);
            check(p.audio.audioRef == null && p.audio.degraded, "failure disguised as speech");
            one(finishPlayback(c, t, 0, 6), Present.class);
            Finished f = one(finishPlayback(c, t, 1, 8), Finished.class);
            check(f.deliveredText.equals("silentspoken") && f.spokenText.equals("spoken"), "silent slot counted as heard");
        });
        test("duplicate and unreserved synthesis callbacks cannot alter the queue", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0); sentence(c, t, "one", 1);
            one(send(c, new Synthesized(t, 0, audio("owned", "one")), 2), Present.class);
            check(send(c, new Synthesized(t, 0, audio("owned", "one")), 3).isEmpty(), "duplicate freed playing resource");
            one(send(c, new Synthesized(t, 9, audio("unreserved", "x")), 4), ReleaseAudio.class);
            one(send(c, new Synthesized(t, 0, audio("duplicate-new-resource", "x")), 5), ReleaseAudio.class);
        });
        test("interrupt clears ready audio and does not invent heard text", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0);
            sentence(c, t, "one", 1); sentence(c, t, "two", 2);
            send(c, new Synthesized(t, 1, audio("buffered", "two")), 3);
            List<Effect> out = send(c, Control.INTERRUPT, 4);
            check(one(out, ReleaseAudio.class).audioRef.equals("buffered"), "buffer leaked");
            check(one(out, Finished.class).deliveredText.isEmpty(), "unplayed text recorded");
        });
        test("timeouts terminate hung synthesis and playback; late result is disposed", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0); sentence(c, t, "one", 1);
            check(one(send(c, new Tick(true, true), 5_001), Finished.class).reason.equals("operation-timeout"), "synthesis hang");
            one(send(c, new Synthesized(t, 0, audio("late", "one")), 5_002), ReleaseAudio.class);
            TurnToken next = user(c, "again", 5_003); sentence(c, next, "next", 5_004);
            send(c, new Synthesized(next, 0, audio("playing", "next")), 5_005); send(c, new PlaybackStarted(next, 0), 5_006);
            one(send(c, new Tick(true, true), 10_006), StopPlayback.class);
            check(c.snapshot().phase == Phase.IDLE, "playback hang");
        });
        test("output budget is bounded and cancels outstanding work", () -> {
            PresenceCore c = started(policy(false, false, false, false, 1)); TurnToken t = user(c, "hi", 0);
            sentence(c, t, "one", 1);
            check(one(send(c, new GeneratedSentence(t, new Sentence("two")), 2), CancelWork.class).reason.equals("output-budget"), "unbounded output");
        });
        test("idle cadence, user quiet time, busy host, audio focus and rate limit", () -> {
            PresenceCore c = started(policy(false, false, true, false, 32));
            check(!has(send(c, new Tick(true, true), 999), Generate.class), "too early");
            check(!has(send(c, new Tick(false, true), 1_000), Generate.class), "busy host ignored");
            check(!has(send(c, new Tick(true, false), 1_001), Generate.class), "audio focus ignored");
            send(c, Control.INPUT_ACTIVITY, 1_002);
            check(!has(send(c, new Tick(true, true), 1_500), Generate.class), "typing quiet period ignored");
            TurnToken t1 = one(send(c, new Tick(true, true), 2_002), Generate.class).turn;
            send(c, new GenerationComplete(t1), 2_003);
            TurnToken t2 = one(send(c, new Tick(true, true), 3_003), Generate.class).turn;
            send(c, new GenerationComplete(t2), 3_004);
            check(!has(send(c, new Tick(true, true), 4_004), Generate.class), "rate quota ignored");
            one(send(c, new Tick(true, true), 62_002), Generate.class);
        });
        test("expired screen context is not reused in new text turns", () -> {
            PresenceCore c = started(policy(false, true, false, false, 32));
            send(c, new Captured(capture(c, 0), sample("old-screen", 0, false)), 0);
            Generate g = one(send(c, new UserText("hi"), 4_001), Generate.class);
            check(g.input.observation == null, "stale screen attached");
        });
        test("capture timeout gets a new ticket; old result cannot overwrite it", () -> {
            PresenceCore c = started(policy(false, true, false, false, 32)); CaptureTicket first = capture(c, 0);
            List<Effect> out = send(c, new Tick(true, true), 5_000);
            one(out, CancelCapture.class); CaptureTicket second = one(out, Capture.class).ticket;
            one(send(c, new Captured(first, sample("old", 5_001, false)), 5_001), DiscardObservation.class);
            send(c, new Captured(second, sample("new", 5_002, false)), 5_002);
            check("new".equals(c.snapshot().observationId), "new capture overwritten");
        });
        test("punctuation-only output uses synchronized caption without TTS", () -> {
            PresenceCore c = started(policy(false, false, false, false, 32)); TurnToken t = user(c, "hi", 0);
            Present p = one(send(c, new GeneratedSentence(t, new Sentence("！？ ")), 1), Present.class);
            check(p.audio.audioRef == null, "punctuation synthesized");
        });
        test("audio envelope handles invalid samples and bounds values", () -> {
            Audio a = new Audio("ref", new Sentence("x"), Arrays.asList(Float.NaN, -1f, 0.3f, 2f), 0, false);
            check(a.volumes.equals(Arrays.asList(0f, 0f, 0.3f, 1f)) && a.sliceLengthMs == 1, "invalid envelope");
        });
        System.out.println("RESULT " + passed + " tests passed");
    }
}
