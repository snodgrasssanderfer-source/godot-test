package io.deepseekharness.mobile.companion.presence;

import static io.deepseekharness.mobile.companion.presence.PresenceProtocol.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PresenceControllerTest {
    private static int passed;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void test(String name, Runnable run) { run.run(); passed++; System.out.println("PASS " + name); }
    private static PresenceEventController.Listener listener(List<String> failures) {
        return new PresenceEventController.Listener() {
            @Override public void onSnapshot(Snapshot snapshot) {}
            @Override public void onPortFailure(String category) { failures.add(category); }
        };
    }
    public static void main(String[] args) {
        test("synchronous callbacks are queued; pause fences stale model result", () -> {
            List<Effect> effects = new ArrayList<>(); List<String> failures = new ArrayList<>();
            PresenceEventController c = new PresenceEventController(Runnable::run, () -> 0, (effect, events) -> {
                effects.add(effect);
                if (effect instanceof Generate) {
                    Generate g = (Generate) effect;
                    events.pause();
                    events.dispatch(new GeneratedSentence(g.turn, new Sentence("late answer")));
                }
            }, listener(failures));
            c.start("session", Policy.disabled()); c.userText("hello");
            check(c.snapshot().phase == Phase.PAUSED, "pause missing");
            check(effects.stream().noneMatch(e -> e instanceof Synthesize), "stale answer synthesized");
            check(effects.stream().anyMatch(e -> e instanceof CancelWork), "cancel missing");
            check(failures.isEmpty(), "unexpected failure");
        });
        test("host final-write barrier rejects cancelled turn and accepts current turn", () -> {
            List<String> failures = new ArrayList<>(); AtomicInteger writes = new AtomicInteger();
            PresenceEventController c = new PresenceEventController(Runnable::run, () -> 0, (e, s) -> {}, listener(failures));
            c.start("s", Policy.disabled()); c.userText("one"); TurnToken old = c.snapshot().turn;
            c.userText("two"); TurnToken current = c.snapshot().turn;
            check(!c.commitIfCurrent(old, writes::incrementAndGet), "old write accepted");
            check(c.commitIfCurrent(current, writes::incrementAndGet) && writes.get() == 1, "current write missing");
            c.stop(); check(!c.commitIfCurrent(current, writes::incrementAndGet), "stopped write accepted");
        });
        test("TTS port exception becomes silent caption slot with observable degradation", () -> {
            List<String> failures = new ArrayList<>(); List<Present> presented = new ArrayList<>();
            PresenceEventController c = new PresenceEventController(Runnable::run, () -> 0, (effect, events) -> {
                if (effect instanceof Generate) {
                    TurnToken t = ((Generate) effect).turn;
                    events.dispatch(new GeneratedSentence(t, new Sentence("caption"))); events.dispatch(new GenerationComplete(t));
                } else if (effect instanceof Synthesize) throw new IllegalStateException("do not log private data");
                else if (effect instanceof Present) presented.add((Present) effect);
            }, listener(failures));
            c.start("s", Policy.disabled()); c.userText("hello");
            check(presented.size() == 1 && presented.get(0).audio.degraded && presented.get(0).audio.audioRef == null, "no degradation");
            check(failures.size() == 1 && failures.get(0).equals("effect-Synthesize"), "error category leaked text");
        });
        test("failed stop port does not skip remaining resource cleanup", () -> {
            List<String> failures = new ArrayList<>(); List<Effect> effects = new ArrayList<>();
            PresenceEventController c = new PresenceEventController(Runnable::run, () -> 0, (effect, events) -> {
                effects.add(effect);
                if (effect instanceof StopPlayback) throw new IllegalStateException("device error");
            }, listener(failures));
            c.start("s", Policy.disabled()); c.userText("hello"); c.interrupt();
            check(effects.stream().anyMatch(e -> e instanceof ResetPresentation), "presentation cleanup skipped");
            check(effects.stream().anyMatch(e -> e instanceof Finished), "terminal event skipped");
            check("effect-StopPlayback".equals(c.lastPortFailure()), "physical stop failure hidden");
        });
        System.out.println("RESULT " + passed + " tests passed");
    }
}
