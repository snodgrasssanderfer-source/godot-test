import type { PresenceState } from './contract'
import { type CharacterPresentation, Emotion, type CharacterSession, type PlaybackPresentation, type VoiceSample } from './presentation'
import { EMOTION_VALUES } from './airi/emotions'

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Maps authoritative host state into display state. It never polls or sends commands.
 * Optional revision is monotonic for this subscription. The host must discard stale native generations.
 * Optional audio {sequence, volume, weights} is played assistant audio, not microphone input.
 */
export class PresenceStateAdapter {
  private session?: CharacterSession
  private sessionId = ''
  private ticket?: PlaybackPresentation
  private playbackId = ''
  private lastPhase = ''
  private lastRevision = -1
  private sampleSequence: string | number | undefined
  private cueKey = ''
  private interruptionBlocked = false
  private character: CharacterPresentation

  constructor(character: CharacterPresentation) { this.character = character }

  update(state: PresenceState): void {
    if (typeof state.scope === 'string' && state.scope !== 'owner:local') {
      this.session?.dispose()
      this.session = undefined
      this.ticket = undefined
      return
    }
    if (typeof state.revision === 'number' && Number.isFinite(state.revision)) {
      if (state.revision <= this.lastRevision) return
      this.lastRevision = state.revision
    }
    if (!state.running) {
      this.session?.dispose()
      this.session = undefined
      this.sessionId = ''
      this.ticket = undefined
      this.lastPhase = ''
      this.interruptionBlocked = false
      return
    }
    const sessionId = typeof state.sessionId === 'string' && state.sessionId ? state.sessionId : 'host-presence'
    if (!this.session || this.sessionId !== sessionId) {
      this.session = this.character.attachSession(sessionId, 'owner:local')
      this.sessionId = sessionId
      this.ticket = undefined
      this.playbackId = ''
      this.lastPhase = ''
      this.cueKey = ''
      this.interruptionBlocked = false
    }
    const paused = state.phase === 'paused' || state.phase === 'error'
    this.session.setState(paused ? 'paused' : 'active')
    this.session.setActivity(state.phase === 'thinking' ? 'thinking' : state.phase === 'listening' ? 'listening' : 'idle')
    const playbackId = typeof state.playbackId === 'string' ? state.playbackId : 'phase-only'
    if (state.phase === 'speaking') {
      if (this.interruptionBlocked && playbackId === this.playbackId) return
      this.interruptionBlocked = false
      if (!this.ticket || this.playbackId !== playbackId) {
        this.ticket?.interrupted()
        this.ticket = this.session.preparePlayback(playbackId)
        this.playbackId = playbackId
        this.sampleSequence = undefined
        this.ticket.started()
      }
      const audio = state.audio
      if (record(audio) && (typeof audio.sequence === 'number' || typeof audio.sequence === 'string') && audio.sequence !== this.sampleSequence && typeof audio.volume === 'number') {
        this.sampleSequence = audio.sequence
        const sample: VoiceSample = { volume: audio.volume }
        if (record(audio.weights)) {
          const raw = audio.weights
          sample.weights = Object.fromEntries(['A', 'E', 'I', 'O', 'U', 'S'].map(key => [key, typeof raw[key] === 'number' ? raw[key] : 0]))
        }
        this.ticket.sample(sample)
      }
    } else {
      this.ticket?.ended()
      this.ticket = undefined
      if (paused) this.interruptionBlocked = true
      else this.interruptionBlocked = false
    }
    const expression = record(state.expression) ? state.expression : undefined
    const emotion = expression && EMOTION_VALUES.includes(expression.emotion as Emotion) ? expression.emotion as Emotion : state.phase === 'thinking' ? Emotion.Think : state.phase === 'observing' || state.phase === 'listening' ? Emotion.Curious : Emotion.Neutral
    const cueKey = `${state.phase}:${emotion}:${typeof expression?.intensity === 'number' ? expression.intensity : 0.65}`
    if (cueKey !== this.cueKey || this.lastPhase !== state.phase) {
      this.session.react({ emotion, intensity: typeof expression?.intensity === 'number' ? expression.intensity : 0.65, durationMs: 3000 })
      this.cueKey = cueKey
    }
    this.lastPhase = state.phase
  }

  /** Immediate visual acknowledgement. The host command still owns actual audio cancellation. */
  interrupt(): void {
    this.ticket?.interrupted()
    this.ticket = undefined
    this.interruptionBlocked = true
  }

  dispose(): void { this.session?.dispose(); this.session = undefined }
}
