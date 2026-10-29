import type { PresencePhase } from './contract'

export type ArtworkLayout = 'portrait' | 'sprite-4x6'
export interface CharacterArtwork { image: HTMLImageElement; width: number; height: number; layout: ArtworkLayout; dispose: () => void }

/** Check the PNG dimensions before decoding. Local files never become network URLs or model inputs. */
export function inspectCharacterPng(bytes: Uint8Array, layout: ArtworkLayout) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10]
  if (bytes.length < 33 || bytes.length > 8 * 1024 * 1024 || !signature.every((v, i) => bytes[i] === v)) throw new Error('请选择不超过 8MB 的 PNG 文件。')
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (view.getUint32(8) !== 13 || String.fromCharCode(...bytes.slice(12, 16)) !== 'IHDR') throw new Error('PNG 头部无效。')
  const width = view.getUint32(16), height = view.getUint32(20)
  if (width < 1 || height < 1 || width > 4096 || height > 4096 || width * height > 8_388_608) throw new Error('图片尺寸需在 4096×4096 以内，总像素不超过 800 万。')
  if (layout === 'sprite-4x6' && (width % 4 !== 0 || height % 6 !== 0)) throw new Error('动作图集需为 4 列 × 6 行，宽高须可整除。')
  return { width, height }
}

export async function loadCharacterPng(file: File, layout: ArtworkLayout): Promise<CharacterArtwork> {
  if (file.size > 8 * 1024 * 1024) throw new Error('请选择不超过 8MB 的 PNG 文件。')
  const bytes = new Uint8Array(await file.arrayBuffer())
  const dimensions = inspectCharacterPng(bytes, layout)
  const url = URL.createObjectURL(new Blob([bytes], { type: 'image/png' }))
  const image = new Image()
  try {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('这张 PNG 无法解码。'))
      image.src = url
    })
    if (image.naturalWidth !== dimensions.width || image.naturalHeight !== dimensions.height) throw new Error('图片尺寸与文件头不一致。')
    return { image, ...dimensions, layout, dispose: () => { image.src = ''; URL.revokeObjectURL(url) } }
  } catch (error) { URL.revokeObjectURL(url); throw error }
}

/** Rows: idle/observing, listening, thinking, speaking, paused, error. Sprite speech is illustrative, not lip sync. */
export function spriteFrame(art: Pick<CharacterArtwork, 'width' | 'height' | 'layout'>, phase: PresencePhase, timeMs: number, reducedMotion: boolean) {
  if (art.layout === 'portrait') return { x: 0, y: 0, width: art.width, height: art.height }
  const rows: Record<PresencePhase, number> = { idle: 0, observing: 0, listening: 1, thinking: 2, speaking: 3, paused: 4, error: 5 }
  const col = reducedMotion || phase === 'paused' || phase === 'error' ? 0 : Math.floor(Math.max(0, timeMs) / 180) % 4
  return { x: col * art.width / 4, y: rows[phase] * art.height / 6, width: art.width / 4, height: art.height / 6 }
}
