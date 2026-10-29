/* SPDX-License-Identifier: MIT */
package io.deepseekharness.mobile.companion.presence;

import static io.deepseekharness.mobile.companion.presence.PresenceProtocol.*;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Kotlin-callable event controller. Supply the host's serialized executor/Handler,
 * elapsedRealtime clock and nonblocking effect adapter. No new model client/queue.
 * All callbacks must retain the original turn/capture ticket; never look up the
 * current token when an old asynchronous operation completes.
 */
public final class PresenceEventController {
    public interface EffectHandler {
        /** Schedule platform work and return immediately. Do not perform blocking I/O here. */
        void handle(Effect effect, PresenceEventController events);
    }
    public interface Listener {
        void onSnapshot(Snapshot snapshot);
        /** Category only, never transcript, screen content, credentials or provider error bodies. */
        void onPortFailure(String category);
    }
    private final PresenceCore core = new PresenceCore();
    private final Object stateLock = new Object(), queueLock = new Object();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Executor executor;
    private final LongSupplier monotonicClock;
    private final EffectHandler effects;
    private final Listener listener;
    private boolean scheduled;
    private volatile Snapshot snapshot = core.snapshot();
    private volatile String lastPortFailure;

    public PresenceEventController(Executor executor, LongSupplier monotonicClock, EffectHandler effects, Listener listener) {
        this.executor = Objects.requireNonNull(executor); this.monotonicClock = Objects.requireNonNull(monotonicClock);
        this.effects = Objects.requireNonNull(effects); this.listener = Objects.requireNonNull(listener);
    }
    public Snapshot snapshot() { return snapshot; }
    public String lastPortFailure() { return lastPortFailure; }
    public void start(String sessionId, Policy policy) { dispatch(new Start(sessionId, policy)); }
    public void pause() { dispatch(Control.PAUSE); }
    public void resume() { dispatch(Control.RESUME); }
    public void stop() { dispatch(Control.STOP); }
    public void interrupt() { dispatch(Control.INTERRUPT); }
    public void userText(String text) { dispatch(new UserText(text)); }
    public void speechStarted(InputTicket ticket, String utteranceId) { dispatch(new SpeechStarted(ticket, utteranceId)); }
    public void recognizedSpeech(InputTicket ticket, String utteranceId, String text) { dispatch(new RecognizedSpeech(ticket, utteranceId, text)); }
    public void setPolicy(Policy policy) { dispatch(new PolicyChanged(policy)); }
    public void tick(boolean modelAvailable, boolean audioFocus) { dispatch(new Tick(modelAvailable, audioFocus)); }

    public void dispatch(Event event) {
        Objects.requireNonNull(event);
        synchronized (queueLock) {
            events.addLast(event);
            if (scheduled) return;
            scheduled = true;
        }
        try { executor.execute(this::drain); }
        catch (RuntimeException error) {
            synchronized (queueLock) { scheduled = false; events.clear(); }
            // A dead executor cannot perform native cleanup. Surface the condition, never claim stopped.
            report("event-executor-unavailable");
            throw error;
        }
    }
    /** Atomic final commit gate for the host's short persistence/presentation mutation.
     * Do not run network requests or await other threads inside commit.
     */
    public boolean commitIfCurrent(TurnToken turn, Runnable commit) {
        synchronized (stateLock) {
            if (turn == null || !turn.equals(snapshot.turn)) return false;
            commit.run(); return true;
        }
    }
    public boolean isCurrent(TurnToken turn) { return turn != null && turn.equals(snapshot.turn); }

    private void drain() {
        while (true) {
            Event event;
            synchronized (queueLock) {
                event = events.pollFirst();
                if (event == null) { scheduled = false; return; }
            }
            List<Effect> output;
            try {
                synchronized (stateLock) {
                    output = core.accept(event, monotonicClock.getAsLong());
                    snapshot = core.snapshot();
                }
            } catch (RuntimeException error) { report("invalid-presence-event"); continue; }
            for (Effect effect : output) {
                if (!mayExecute(effect)) continue;
                try { effects.handle(effect, this); }
                catch (RuntimeException error) {
                    report("effect-" + effect.getClass().getSimpleName());
                    if (effect instanceof Synthesize) {
                        Synthesize e = (Synthesize) effect; dispatch(new SynthesisFailed(e.turn, e.sequence));
                    } else if (effect instanceof Capture) dispatch(new CaptureFailed(((Capture) effect).ticket));
                    else {
                        TurnToken turn = workTurn(effect);
                        if (turn != null) dispatch(new Failed(turn, "host-port-failed"));
                    }
                }
            }
            try { listener.onSnapshot(snapshot); }
            catch (RuntimeException error) { report("snapshot-listener-failed"); }
        }
    }
    private TurnToken workTurn(Effect effect) {
        if (effect instanceof Generate) return ((Generate) effect).turn;
        if (effect instanceof Transcribe) return ((Transcribe) effect).turn;
        if (effect instanceof Synthesize) return ((Synthesize) effect).turn;
        if (effect instanceof Present) return ((Present) effect).turn;
        return null;
    }
    private boolean mayExecute(Effect effect) {
        TurnToken turn = workTurn(effect);
        if (turn != null) return isCurrent(turn);
        if (effect instanceof Listen) return Objects.equals(((Listen) effect).ticket, snapshot.inputTicket) &&
            snapshot.phase != Phase.PAUSED && snapshot.phase != Phase.STOPPED;
        // Cancel/stop/dispose effects MUST execute for obsolete tokens too.
        return true;
    }
    private void report(String category) {
        lastPortFailure = category;
        try { listener.onPortFailure(category); } catch (RuntimeException ignored) { /* Never block cleanup. */ }
    }
}
