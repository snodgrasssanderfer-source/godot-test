import { Emotion, EMOTION_VALUES } from './airi/emotions'
import { randomSaccadeInterval } from './airi/eye-motions'
import { createWLipSyncVowelDriver, type WLipSyncFrame, type WLipSyncVowelWeights } from './airi/vowel-driver'

export { Emotion } from './airi/emotions'

export type SessionState = 'active' | 'paused' | 'stopped'
export type Activity = 'idle' | 'listening' | 'thinking'
export type MouthSource = 'unavailable' | 'amplitude' | 'viseme'

/** A cue describes a display response. It never starts speech or a model request. */
export interface ExpressionCue {
  emotion: Emotion
  intensity?: number
  durationMs?: number
}

/** Values must describe the assistant's playing audio, never the microphone input. */
export interface VoiceSample {
  volume: number
  weights?: WLipSyncFrame['weights']
}

export interface CharacterFrame {
  state: SessionState
  activity: Activity
  speaking: boolean
  mouthSource: MouthSource
  mouthOpen: number
  vowels: Readonly<WLipSyncVowelWeights>
  emotion: Emotion
  intensity: number
  gazeX: number
  gazeY: number
  blink: number
  breath: number
}

/**
 * Create a ticket before asynchronous synthesis. Call started only after playback starts.
 * Every accepted ticket needs ended, interrupted, or rejected. Terminal calls are idempotent.
 * A pause or session switch invalidates existing tickets, including queued tickets.
 */
export interface PlaybackPresentation {
  started: () => boolean
  sample: (value: VoiceSample) => void
  ended: () => void
  interrupted: () => void
  rejected: () => void
}

/** This port belongs to one host session. Dispose it when the host session ends. */
export interface CharacterSession {
  setState: (value: SessionState) => void
  setActivity: (value: Activity) => void
  react: (cue: ExpressionCue) => void
  preparePlayback: (playbackId: string, cue?: ExpressionCue) => PlaybackPresentation
  dispose: () => void
}

export interface PresentationOptions {
  /** Must match the host's selected conversation domain. @default 'owner:local' */
  scope?: string
  /** Monotonic milliseconds. @default performance.now */
  now?: () => number
  /** Used only for procedural gaze targets. @default Math.random */
  random?: () => number
}

const ZERO_VOWELS: Readonly<WLipSyncVowelWeights> = Object.freeze({ A: 0, E: 0, I: 0, O: 0, U: 0 })
const SILENCE: WLipSyncFrame = Object.freeze({ volume: 0, weights: ZERO_VOWELS })
const NO_PLAYBACK: PlaybackPresentation = Object.freeze({
  started: () => false, sample: () => {}, ended: () => {}, interrupted: () => {}, rejected: () => {},
})

function unit(value: number | undefined): number {
  return Number.isFinite(value) ? Math.min(1, Math.max(0, value!)) : 0
}

/**
 * Presentation state only. The host owns perception, dialogue, audio, and session policy.
 * Session capabilities prevent late callbacks from one session changing another session.
 * A playback ordinal prevents old completion callbacks from closing a newer mouth.
 */
export class CharacterPresentation {
  private readonly scope: string
  private readonly now: () => number
  private readonly random: () => number
  private readonly vowels
  private readonly listeners = new Set<() => void>()
  private generation = 0
  private epoch = 0
  private ordinal = 0
  private highestStarted = 0
  private activePlayback: number | undefined
  private state: SessionState = 'stopped'
  private activity: Activity = 'idle'
  private voice: WLipSyncFrame = SILENCE
  private voiceAt = -Infinity
  private mouthSource: MouthSource = 'unavailable'
  private emotion = Emotion.Neutral
  private intensity = 0
  private emotionUntil = -Infinity
  private nextSaccade = 0
  private gazeX = 0
  private gazeY = 0
  private disposed = false

  constructor(options: PresentationOptions = {}) {
    this.scope = options.scope ?? 'owner:local'
    this.now = options.now ?? (() => performance.now())
    this.random = options.random ?? Math.random
    this.vowels = createWLipSyncVowelDriver(this.now)
  }

  /**
   * Call after the host explicitly selects or starts a session. No permission is requested here.
   * Reattaching invalidates the previous port. Other conversation domains are rejected.
   */
  attachSession(sessionId: string, scope: string): CharacterSession {
    if (this.disposed) throw new Error('Character presentation is disposed')
    if (!sessionId.trim()) throw new Error('A host session ID is required')
    if (scope !== this.scope) throw new Error('Character scope does not match the host session')
    const generation = ++this.generation
    this.state = 'active'
    this.activity = 'idle'
    this.epoch++
    this.resetPlayback()
    this.resetExpression()
    this.notify()
    let closed = false
    const current = () => !closed && !this.disposed && generation === this.generation
    const active = () => current() && this.state === 'active'

    const stop = () => {
      if (!current()) return
      closed = true
      this.state = 'stopped'
      this.epoch++
      this.activity = 'idle'
      this.resetPlayback()
      this.resetExpression()
      this.notify()
    }

    return {
      setState: value => {
        if (!current() || value === this.state) return
        if (value === 'stopped') { stop(); return }
        this.state = value
        this.epoch++
        this.activity = 'idle'
        this.resetPlayback()
        this.resetExpression()
        this.notify()
      },
      setActivity: value => {
        if (!active()) return
        this.activity = value
        this.notify()
      },
      react: cue => {
        if (!active()) return
        this.applyCue(cue)
        this.notify()
      },
      preparePlayback: (playbackId, cue) => {
        if (!active() || !playbackId.trim()) return NO_PLAYBACK
        const epoch = this.epoch
        const ordinal = ++this.ordinal
        let terminal = false
        let started = false
        const valid = () => active() && epoch === this.epoch && !terminal
        const ownsVoice = () => valid() && this.activePlayback === ordinal
        const finish = () => {
          if (ownsVoice()) {
            this.resetPlayback()
            this.resetExpression()
            this.notify()
          }
          terminal = true
        }
        return {
          started: () => {
            if (!valid() || ordinal < this.highestStarted) return false
            if (started) return true
            started = true
            this.highestStarted = ordinal
            this.resetPlayback()
            this.resetExpression()
            this.activePlayback = ordinal
            if (cue) this.applyCue(cue)
            this.notify()
            return true
          },
          sample: value => {
            if (!ownsVoice()) return
            this.voiceAt = this.now()
            this.mouthSource = value.weights ? 'viseme' : 'amplitude'
            // Clamp before AIRI's fractional exponent. Negative/NaN bridge values must not poison the driver.
            this.voice = {
              volume: unit(value.volume),
              weights: value.weights ? {
                A: unit(value.weights.A), E: unit(value.weights.E), I: unit(value.weights.I),
                O: unit(value.weights.O), U: unit(value.weights.U), S: unit(value.weights.S),
              } : { A: 1 },
            }
          },
          ended: finish,
          interrupted: finish,
          rejected: finish,
        }
      },
      dispose: stop,
    }
  }

  /** Returns fresh visual values. No audio, permission, storage, or network side effects. */
  frame(deltaSeconds = 1 / 30, reducedMotion = false): CharacterFrame {
    const now = this.now()
    const active = this.state === 'active' && !this.disposed
    const speaking = active && this.activePlayback !== undefined
    // Lost telemetry closes the mouth within 200 ms. It does not invent a playback completion.
    const freshAudio = speaking && now - this.voiceAt >= 0 && now - this.voiceAt <= 200
    const delta = Number.isFinite(deltaSeconds) ? Math.min(0.1, Math.max(0, deltaSeconds)) : 0
    let vowels = ZERO_VOWELS
    if (freshAudio) vowels = this.vowels.update(this.voice, delta)
    else this.vowels.reset()
    if (now >= this.emotionUntil) this.resetExpression()
    if (active && !reducedMotion && now >= this.nextSaccade) {
      this.gazeX = (unit(this.random()) * 2 - 1) * 0.45
      this.gazeY = (unit(this.random()) * 1.7 - 1) * 0.35
      this.nextSaccade = now + randomSaccadeInterval()
    }
    // A short periodic blink is display motion, never a perception signal.
    const blinkPhase = (now % 4300) / 160
    const blink = blinkPhase < 1 ? Math.sin(blinkPhase * Math.PI) : 0
    return {
      state: this.state, activity: this.activity, speaking,
      mouthSource: freshAudio ? this.mouthSource : 'unavailable',
      mouthOpen: Math.max(vowels.A, vowels.E, vowels.I, vowels.O, vowels.U),
      vowels, emotion: this.emotion, intensity: this.intensity,
      gazeX: active && !reducedMotion ? this.gazeX : 0,
      gazeY: active && !reducedMotion ? this.gazeY : 0,
      blink: active && !reducedMotion ? blink : 0,
      breath: active && !reducedMotion ? Math.sin(now / 1100) * 0.015 : 0,
    }
  }

  /** Invalidations announce state transitions. Audio samples are read by the capped render loop. */
  subscribe(listener: () => void): () => void {
    if (this.disposed) return () => {}
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.generation++
    this.state = 'stopped'
    this.activity = 'idle'
    this.resetPlayback()
    this.resetExpression()
    this.notify()
    this.listeners.clear()
  }

  private applyCue(cue: ExpressionCue): void {
    if (!EMOTION_VALUES.includes(cue.emotion)) return
    this.emotion = cue.emotion
    this.intensity = unit(cue.intensity ?? 0.7)
    const duration = cue.durationMs ?? 1600
    this.emotionUntil = this.now() + (Number.isFinite(duration) ? Math.min(5000, Math.max(0, duration)) : 0)
  }

  private resetPlayback(): void {
    this.activePlayback = undefined
    this.voice = SILENCE
    this.voiceAt = -Infinity
    this.mouthSource = 'unavailable'
    this.vowels.reset()
  }

  private resetExpression(): void {
    this.emotion = Emotion.Neutral
    this.intensity = 0
    this.emotionUntil = -Infinity
  }

  private notify(): void {
    for (const listener of this.listeners) listener()
  }
}
