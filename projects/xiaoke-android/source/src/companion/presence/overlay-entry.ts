import { CharacterPresentation } from './presentation'
import { PresenceStateAdapter } from './state-adapter'
import { mountCharacterStage } from './character-stage'
import { phaseLabels, type PresenceState } from './contract'
import { loadCharacterPng, type CharacterArtwork } from './character-art'

const presentation = new CharacterPresentation()
const adapter = new PresenceStateAdapter(presentation)
const stage = mountCharacterStage(document.getElementById('character')!, presentation, {framesPerSecond: 18})
const caption = document.getElementById('caption')!
let artwork: CharacterArtwork | undefined
let artworkKey = '', generation = 0
const bridge = window as typeof window & {setCompanionPresenceState?: (value: PresenceState) => void}
bridge.setCompanionPresenceState = value => {
  adapter.update(value)
  stage.setVisible(value.running && value.phase !== 'paused')
  caption.textContent = value.running ? value.text?.slice(-80) || phaseLabels[value.phase] : `${value.displayName || '小克'} · 点我聊天`
  const saved = value.character as {version?: number; overlayUrl?: string; layout?: 'portrait'|'sprite-4x6'} | undefined
  const nextKey = saved?.version ? String(saved.version) : ''
  if (nextKey !== artworkKey) {
    artworkKey = nextKey; const token = ++generation
    if (saved?.overlayUrl === 'https://xiaoke.local/character.png' && saved.layout) {
      void fetch(`${saved.overlayUrl}?v=${nextKey}`).then(response => { if (!response.ok) throw new Error('local artwork unavailable'); return response.blob() })
        .then(blob => loadCharacterPng(new File([blob], 'character.png', {type:'image/png'}), saved.layout!))
        .then(loaded => { if (token !== generation) { loaded.dispose(); return } artwork?.dispose(); artwork = loaded; stage.setArtwork(loaded, value.phase) })
        .catch(() => { if (token === generation) { artwork?.dispose(); artwork = undefined; stage.setArtwork(undefined, value.phase) } })
    } else { artwork?.dispose(); artwork = undefined; stage.setArtwork(undefined, value.phase) }
  } else stage.setArtwork(artwork, value.phase)
}
window.addEventListener('unload', () => { generation++; artwork?.dispose(); stage.dispose(); adapter.dispose(); presentation.dispose(); delete bridge.setCompanionPresenceState })
