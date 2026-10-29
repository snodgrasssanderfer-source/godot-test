import { Emotion, type CharacterFrame, type CharacterPresentation } from './presentation'
import { spriteFrame, type CharacterArtwork } from './character-art'
import type { PresencePhase } from './contract'

export interface StageOptions {
  /** Accessible description of the local placeholder. @default '角色状态预览' */
  label?: string
  /** @default 24 */
  framesPerSecond?: number
}

/**
 * Mount a small original geometric placeholder inside a host-owned element.
 * It never focuses, captures input, overlays other apps, or obtains media permissions.
 * The host supplies licensed Live2D/VRM renderers separately through model-bindings.ts.
 */
export function mountCharacterStage(container: HTMLElement, presentation: CharacterPresentation, options: StageOptions = {}) {
  const doc = container.ownerDocument
  const win = doc.defaultView
  if (!win) throw new Error('The character stage needs a document window')
  const canvas = doc.createElement('canvas')
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas 2D is unavailable')
  canvas.setAttribute('role', 'img')
  canvas.setAttribute('aria-label', options.label ?? '角色状态预览')
  canvas.style.cssText = 'display:block;width:100%;max-width:144px;aspect-ratio:1/1;pointer-events:none;user-select:none'
  container.appendChild(canvas)
  const ratio = Math.min(1.5, win.devicePixelRatio || 1)
  canvas.width = Math.round(144 * ratio)
  canvas.height = Math.round(144 * ratio)
  context.scale(ratio, ratio)
  const requestedFps = options.framesPerSecond ?? 24
  const interval = 1000 / (Number.isFinite(requestedFps) ? Math.min(30, Math.max(1, requestedFps)) : 24)
  const motionPreference = win.matchMedia('(prefers-reduced-motion: reduce)')
  let frameId: number | undefined
  let lastDrawAt = -Infinity
  let visible = true
  let disposed = false
  let artwork: CharacterArtwork | undefined
  let phase: PresencePhase = 'idle'

  const cancel = () => {
    if (frameId !== undefined) win.cancelAnimationFrame(frameId)
    frameId = undefined
  }

  const render = (now: number, force = false) => {
    if (disposed || !visible || doc.hidden) return
    if (!force && now - lastDrawAt < interval) return
    const delta = Number.isFinite(lastDrawAt) ? (now - lastDrawAt) / 1000 : 1 / 24
    const frame = presentation.frame(delta, motionPreference.matches)
    if (artwork) {
      context.clearRect(0, 0, 144, 144)
      const tile = spriteFrame(artwork, phase, now, motionPreference.matches || frame.state !== 'active')
      const scale = Math.min(136 / tile.width, 136 / tile.height)
      context.drawImage(artwork.image, tile.x, tile.y, tile.width, tile.height, (144 - tile.width * scale) / 2, (144 - tile.height * scale) / 2, tile.width * scale, tile.height * scale)
    } else drawPlaceholder(context, frame)
    canvas.dataset.state = frame.state
    canvas.dataset.speaking = String(frame.speaking)
    canvas.dataset.mouthSource = frame.mouthSource
    lastDrawAt = now
  }

  const tick = (now: number) => {
    frameId = undefined
    if (disposed || !visible || doc.hidden) return
    render(now)
    const frame = presentation.frame(0, motionPreference.matches)
    if (frame.state === 'active') frameId = win.requestAnimationFrame(tick)
  }

  const refresh = () => {
    cancel()
    if (disposed || !visible || doc.hidden) return
    render(win.performance.now(), true)
    if (presentation.frame(0, motionPreference.matches).state === 'active') {
      frameId = win.requestAnimationFrame(tick)
    }
  }

  const unsubscribe = presentation.subscribe(refresh)
  doc.addEventListener('visibilitychange', refresh)
  motionPreference.addEventListener('change', refresh)
  refresh()

  return {
    setArtwork(value: CharacterArtwork | undefined, nextPhase: PresencePhase) { artwork = value; phase = nextPhase; refresh() },
    /** Hiding stops the display loop. It does not pause the host's companion session. */
    setVisible(value: boolean) { visible = value; refresh() },
    dispose() {
      if (disposed) return
      disposed = true
      cancel()
      unsubscribe()
      doc.removeEventListener('visibilitychange', refresh)
      motionPreference.removeEventListener('change', refresh)
      canvas.remove()
    },
  }
}

function drawPlaceholder(ctx: CanvasRenderingContext2D, frame: CharacterFrame): void {
  ctx.clearRect(0, 0, 144, 144)
  ctx.save()
  ctx.translate(72, 73 + frame.breath * 144)
  ctx.rotate(frame.activity === 'thinking' ? -0.1 : frame.activity === 'listening' ? 0.06 : 0)
  ctx.globalAlpha = frame.state === 'active' ? 1 : 0.6
  ctx.fillStyle = '#b6c9ff'
  ctx.beginPath()
  ctx.ellipse(0, 0, 49, 50, 0, 0, Math.PI * 2)
  ctx.fill()
  ctx.strokeStyle = '#34415b'
  ctx.lineWidth = 2.4
  ctx.stroke()
  ctx.fillStyle = '#f0f4ff'
  ctx.beginPath()
  ctx.ellipse(-19, -24, 14, 6, -0.5, 0, Math.PI * 2)
  ctx.fill()
  ctx.fillStyle = '#26334f'
  for (const x of [-17, 17]) {
    ctx.beginPath()
    ctx.ellipse(x + frame.gazeX * 6, -5 + frame.gazeY * 5, 4, Math.max(0.7, 6 * (1 - frame.blink)), 0, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.lineCap = 'round'
  if (frame.emotion === Emotion.Happy) {
    ctx.strokeStyle = '#798ee1'
    for (const x of [-29, 29]) {
      ctx.beginPath(); ctx.moveTo(x - 3, 7); ctx.lineTo(x + 3, 7); ctx.stroke()
    }
  }
  if (frame.emotion === Emotion.Sad || frame.emotion === Emotion.Angry || frame.emotion === Emotion.Think) {
    ctx.strokeStyle = '#26334f'
    const slope = frame.emotion === Emotion.Angry ? 4 : -4
    for (const side of [-1, 1]) {
      ctx.beginPath(); ctx.moveTo(side * 24, -18); ctx.lineTo(side * 12, -18 + slope); ctx.stroke()
    }
  }
  ctx.strokeStyle = '#26334f'
  ctx.fillStyle = '#26334f'
  ctx.beginPath()
  if (frame.mouthOpen > 0.01) {
    ctx.ellipse(0, 20, 6 + frame.vowels.A * 6, 1 + frame.mouthOpen * 23, 0, 0, Math.PI * 2)
    ctx.fill()
  } else {
    ctx.moveTo(-7, 17)
    ctx.quadraticCurveTo(0, frame.emotion === Emotion.Sad ? 13 : 23, 7, 17)
    ctx.stroke()
  }
  ctx.restore()
}
