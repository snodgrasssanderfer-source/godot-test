import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { CharacterPresentation } from './presentation'
import { PresenceStateAdapter } from './state-adapter'
import { mountCharacterStage } from './character-stage'
import { loadCharacterPng, type CharacterArtwork, type ArtworkLayout } from './character-art'
import { phaseLabels, validateConfig, type PresenceConfig, type PresenceMode, type PresencePanelProps } from './contract'
import './presence.css'
import { Capacitor } from '@capacitor/core'

/** Inline controls and animated character for the host's single companion session. */
export function PresencePanel({ state, onCommand }: PresencePanelProps) {
  const labelId = useId()
  const canvasHost = useRef<HTMLDivElement>(null)
  const view = useRef<ReturnType<typeof mountCharacterStage>>()
  const adapter = useRef<PresenceStateAdapter>()
  const latestState = useRef(state)
  latestState.current = state
  const artwork = useRef<CharacterArtwork>()
  const importGeneration = useRef(0)
  const alive = useRef(true)
  const inflight = useRef(new Set<string>())
  const [pending, setPending] = useState<string[]>([])
  const [error, setError] = useState('')
  const [artLabel, setArtLabel] = useState('默认形象')
  const [layout, setLayout] = useState<ArtworkLayout>('sprite-4x6')
  const [showCharacter, setShowCharacter] = useState(true)
  const [observeScreen, setObserveScreen] = useState(false)
  const [apps, setApps] = useState<{package: string; name: string}[]>([])
  const [message, setMessage] = useState('')
  const [config, setConfig] = useState<PresenceConfig>({ package: '', minutes: 20, mode: state.mode, intervalSeconds: 15, voiceInput: false, voiceOutput: true })
  const running = state.running
  const paused = state.phase === 'paused'
  const foreignScope = typeof state.scope === 'string' && state.scope !== 'owner:local'
  const blocked = state.available === false || foreignScope
  const busyStart = pending.includes('presenceStart')
  const mode: PresenceMode = running ? state.mode : config.mode
  const savedCharacter = state.character as {path?: string; version?: number; layout?: ArtworkLayout} | undefined
  const muted = typeof state.muted === 'boolean' ? state.muted : typeof state.voiceOutput === 'boolean' ? !state.voiceOutput : undefined

  useEffect(() => {
    alive.current = true
    const generationRef = importGeneration
    const presentation = new CharacterPresentation()
    const binding = new PresenceStateAdapter(presentation)
    adapter.current = binding
    try {
      if (canvasHost.current) view.current = mountCharacterStage(canvasHost.current, presentation)
      binding.update(latestState.current)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '角色显示暂不可用。') }
    return () => {
      alive.current = false
      generationRef.current++
      view.current?.dispose()
      view.current = undefined
      binding.dispose()
      adapter.current = undefined
      presentation.dispose()
      artwork.current?.dispose()
      artwork.current = undefined
    }
  }, [])

  useEffect(() => {
    adapter.current?.update(state)
    view.current?.setArtwork(artwork.current, state.phase)
  }, [state])
  useEffect(() => { view.current?.setVisible(showCharacter) }, [showCharacter])
  useEffect(() => {
    if (!state.characterStorage || !savedCharacter?.path || !savedCharacter.layout) return
    const token = ++importGeneration.current
    void fetch(Capacitor.convertFileSrc(savedCharacter.path)).then(response => { if (!response.ok) throw new Error('本机角色图片读取失败'); return response.blob() })
      .then(blob => loadCharacterPng(new File([blob], 'character.png', {type:'image/png'}), savedCharacter.layout!))
      .then(loaded => {
        if (!alive.current || token !== importGeneration.current) { loaded.dispose(); return }
        artwork.current?.dispose(); artwork.current = loaded
        view.current?.setArtwork(loaded, latestState.current.phase)
        setArtLabel(savedCharacter.layout === 'portrait' ? '你的形象图' : '你的动作图集')
      }).catch(cause => { if (alive.current && token === importGeneration.current) setError(String(cause)) })
  }, [savedCharacter?.path, savedCharacter?.version, savedCharacter?.layout, state.characterStorage])

  async function send(action: string, data?: object) {
    if (inflight.current.has(action)) return
    inflight.current.add(action)
    setPending([...inflight.current])
    setError('')
    if (action === 'presenceInterrupt' || action === 'presenceStop') adapter.current?.interrupt()
    try { await onCommand(action, data) }
    catch (cause) { if (alive.current) setError(cause instanceof Error ? cause.message : String(cause)) }
    finally {
      inflight.current.delete(action)
      if (alive.current) setPending([...inflight.current])
    }
  }

  function start(event: FormEvent) {
    event.preventDefault()
    const next = { ...config, package: observeScreen ? config.package.trim() : '', voiceOutput: config.voiceOutput && config.mode !== 'silent' }
    if (observeScreen && !next.package) { setError('先选择要观察的应用包名。'); return }
    const problem = validateConfig(next)
    if (problem) { setError(problem); return }
    void send('presenceStart', next)
  }

  async function importArtwork(file: File) {
    const generation = ++importGeneration.current
    setError('')
    try {
      const loaded = await loadCharacterPng(file, layout)
      if (!alive.current || generation !== importGeneration.current) { loaded.dispose(); return }
      const previous = artwork.current
      artwork.current = loaded
      view.current?.setArtwork(loaded, latestState.current.phase)
      previous?.dispose()
      setArtLabel(layout === 'sprite-4x6' ? '你的动作图集' : '你的形象图')
      if (state.characterStorage) {
        const encoded = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader(); reader.onload = () => { if (typeof reader.result === 'string') resolve(reader.result.split(',')[1]); else reject(new Error('文件读取格式不正确')) }; reader.onerror = () => reject(new Error('角色图片读取失败')); reader.readAsDataURL(file)
        })
        await onCommand('saveCharacter', {base64: encoded, layout})
      }
    } catch (cause) {
      if (alive.current && generation === importGeneration.current) setError(cause instanceof Error ? cause.message : '角色图片无法载入。')
    }
  }

  function resetArtwork() {
    importGeneration.current++
    view.current?.setArtwork(undefined, state.phase)
    artwork.current?.dispose()
    artwork.current = undefined
    setArtLabel('默认形象')
    if (state.characterStorage) void send('clearCharacter')
  }

  return <section className="xiaoke-presence" aria-labelledby={labelId}>
    <div className="xp-heading"><div><h2 id={labelId}>{state.displayName || '小克'}</h2></div><span className="xp-status" data-phase={state.phase} role="status">{running ? phaseLabels[state.phase] : '没在陪伴'}</span></div>
    <div className="xp-stage-row">
      <div className="xp-character" hidden={!showCharacter}><div ref={canvasHost}/><small>{artLabel}</small></div>
      <div className="xp-caption"><p>{foreignScope ? '这不是你的陪伴会话。' : state.text ? state.text.slice(0, 320) : running ? '说话或打字都行。' : '开一段语音陪伴，她能听你说、陪你看。'}</p>{!foreignScope && running && typeof state.package === 'string' && <small>{state.package ? `在看：${state.package}` : '没在看屏幕'}</small>}{!foreignScope && typeof state.statusMessage === 'string' && <p>{state.statusMessage}</p>}{paused && <p>暂停着，在聊天页顶部点继续。</p>}</div>
    </div>
    {error && <div className="xp-error" role="alert">{error}</div>}
    {blocked && <p className="xp-error">{typeof state.unavailableReason === 'string' ? state.unavailableReason : '浏览器里用不了语音陪伴。'}</p>}
    <div className="xp-mode" role="group" aria-label="陪伴方式">{(['companion', 'focus', 'silent'] as const).map(value => <button type="button" key={value} aria-pressed={mode === value} disabled={blocked || pending.includes('presenceMode')} onClick={() => { if (running) void send('presenceMode', { mode: value }); else setConfig(current => ({ ...current, mode: value })) }}>{value === 'companion' ? '随时交流' : value === 'focus' ? '专注陪伴' : '静音陪伴'}</button>)}</div>
    <p className="xp-mode-note">{mode === 'focus' ? '少插话，你叫她再说。' : mode === 'silent' ? '只打字，不出声。' : '想说就说。'}</p>
    {running && <form onSubmit={event => { event.preventDefault(); const text = message.trim(); if (!text) return; void send('presenceText', {text}); setMessage('') }}><label>打字给她<input type="text" value={message} onChange={event => setMessage(event.target.value)} placeholder="在做什么…" maxLength={8000}/></label><button type="submit" disabled={!message.trim() || paused || blocked}>发送</button></form>}
    <div className="xp-actions">
      <button type="button" disabled={!running || blocked || pending.includes('presenceInterrupt')} onClick={() => void send('presenceInterrupt')}>打断当前回应</button>
      <button type="button" disabled={!running || paused || blocked || pending.includes('presenceListen')} onClick={() => void send('presenceListen')}>听我说一次</button>
      {muted === undefined ? <button type="button" disabled={!running || blocked || pending.includes('presenceMute')} onClick={() => void send('presenceMute', { muted: true })}>关闭语音输出</button> : <button type="button" aria-pressed={muted} disabled={!running || blocked || pending.includes('presenceMute')} onClick={() => void send('presenceMute', { muted: !muted })}>{muted ? '开启语音输出' : '关闭语音输出'}</button>}
      <button className="xp-stop" type="button" disabled={(!running && !busyStart) || pending.includes('presenceStop')} onClick={() => void send('presenceStop')}>结束陪伴</button>
      {running && <button type="button" disabled={!state.canSaveEvent || blocked || pending.includes('presenceSave')} onClick={() => { if (confirm('把刚才这件事和画面记下来？')) void send('presenceSave') }}>记下刚才这件事</button>}
    </div>
    <form onSubmit={start}>
      <details open={!running}><summary>这次怎么陪</summary>
        <fieldset disabled={running || busyStart || blocked}>
          <label className="xp-check"><input type="checkbox" aria-label="读取选定应用画面" checked={observeScreen} onChange={event => setObserveScreen(event.target.checked)}/>看某个应用的画面</label>
          {observeScreen && <><button type="button" onClick={() => { void onCommand('launchableApps').then(value => { if (Array.isArray(value)) setApps(value as typeof apps) }).catch(cause => setError(String(cause))) }}>从手机应用里选</button>{apps.length > 0 && <label>应用<select value={config.package} onChange={event => setConfig(current => ({...current,package:event.target.value}))}><option value="">选一个</option>{apps.map(app => <option key={app.package} value={app.package}>{app.name}</option>)}</select></label>}<label>仅允许观察此应用（包名）<input type="text" value={config.package} onChange={event => setConfig(current => ({ ...current, package: event.target.value }))} placeholder="com.example.game" autoCapitalize="none" autoCorrect="off" spellCheck={false} maxLength={200}/></label><label>设备控制密码（设过才填）<input type="password" autoComplete="off" value={config.password || ''} onChange={event => setConfig(current => ({...current,password:event.target.value}))}/></label></>}
          <div className="xp-grid"><label>陪多久（分钟）<input type="number" min={1} max={180} step={1} value={config.minutes} onChange={event => setConfig(current => ({ ...current, minutes: Number(event.target.value) }))}/></label><label>多久看一眼（秒）<input type="number" min={5} max={120} step={1} disabled={!observeScreen} value={config.intervalSeconds} onChange={event => setConfig(current => ({ ...current, intervalSeconds: Number(event.target.value) }))}/></label></div>
          <label className="xp-check"><input type="checkbox" aria-label="允许本次持续听取语音" checked={config.voiceInput} onChange={event => setConfig(current => ({ ...current, voiceInput: event.target.checked }))}/>一直听着我说话</label>
          <label>多久没想好就算了（秒）<input type="number" min={1} max={30} value={(config.responseDeadlineMs ?? 8000) / 1000} onChange={event => setConfig(current => ({...current,responseDeadlineMs:Number(event.target.value) * 1000}))}/></label>
          <label className="xp-check"><input type="checkbox" checked={config.voiceOutput} onChange={event => setConfig(current => ({ ...current, voiceOutput: event.target.checked }))}/>她出声说话</label>
          <details><summary>语音细节</summary><label className="xp-check"><input type="checkbox" checked={config.headphoneBargeIn || false} onChange={event => setConfig(current => ({...current,headphoneBargeIn:event.target.checked}))}/>戴耳机时可以直接插话</label><label className="xp-check"><input type="checkbox" checked={config.allowNetworkAsr || false} onChange={event => setConfig(current => ({...current,allowNetworkAsr:event.target.checked}))}/>语音识别可以联网</label><label className="xp-check"><input type="checkbox" checked={config.allowNetworkTts || false} onChange={event => setConfig(current => ({...current,allowNetworkTts:event.target.checked}))}/>语音合成可以联网</label></details>
        </fieldset>
        {running && <p className="xp-mode-note">结束后才能改这些。</p>}
      </details>
      {!running && <button className="xp-start" type="submit" disabled={busyStart || blocked}>{busyStart ? '等待开启结果…' : '开启本次陪伴'}</button>}
    </form>
    <details><summary>形象</summary><label className="xp-check"><input type="checkbox" checked={showCharacter} onChange={event => setShowCharacter(event.target.checked)}/>显示形象动画</label><label>图片格式<select value={layout} onChange={event => setLayout(event.target.value as ArtworkLayout)}><option value="sprite-4x6">动作图集 · 4 列 × 6 行</option><option value="portrait">单张角色图 · 静态</option></select></label><label className="xp-import">换一张 PNG<input type="file" accept="image/png,.png" onChange={event => { const file = event.target.files?.[0]; if (file) void importArtwork(file); event.target.value = '' }}/></label><p className="xp-mode-note">图集 6 行依次是：待机、听、想、说、暂停、出错，每行 4 帧。</p><button type="button" onClick={resetArtwork}>换回默认形象</button></details>
  </section>
}
