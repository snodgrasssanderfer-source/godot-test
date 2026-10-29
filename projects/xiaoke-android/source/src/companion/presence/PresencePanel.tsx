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
  const [artLabel, setArtLabel] = useState('内置动态示意角色')
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
    const presentation = new CharacterPresentation()
    const binding = new PresenceStateAdapter(presentation)
    adapter.current = binding
    try {
      if (canvasHost.current) view.current = mountCharacterStage(canvasHost.current, presentation)
      binding.update(latestState.current)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '角色显示暂不可用。') }
    return () => {
      alive.current = false
      importGeneration.current++
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
        setArtLabel(savedCharacter.layout === 'portrait' ? '你的本机角色图' : '你的本机动作图集 · 示意动画')
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
      setArtLabel(layout === 'sprite-4x6' ? '你的本地动作角色 · 示意动画' : '你的本地角色图 · 静态')
      if (state.characterStorage) {
        const encoded = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(new Error('角色图片读取失败')); reader.readAsDataURL(file)
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
    setArtLabel('内置动态示意角色')
    if (state.characterStorage) void send('clearCharacter')
  }

  return <section className="xiaoke-presence" aria-labelledby={labelId}>
    <div className="xp-heading"><div><p className="xp-eyebrow">陪你看，陪你聊</p><h2 id={labelId}>{state.displayName || '小克'}在这里</h2></div><span className="xp-status" data-phase={state.phase} role="status">{running ? phaseLabels[state.phase] : '尚未开启陪伴'}</span></div>
    <div className="xp-stage-row">
      <div className="xp-character" hidden={!showCharacter}><div ref={canvasHost}/><small>{artLabel}</small></div>
      <div className="xp-caption"><p>{foreignScope ? '当前数据不属于本机陪伴会话。' : state.text ? state.text.slice(0, 320) : running ? '你可以用文字、语音交流，或点击打断当前回应。' : '由你开启本次会话，决定看什么、听什么，以及陪多久。'}</p><small>{!foreignScope && running && typeof state.package === 'string' ? state.package ? `观察范围：${state.package}` : '本次不观察屏幕' : '只感知你明确开启的范围'}</small>{!foreignScope && typeof state.statusMessage === 'string' && <p>{state.statusMessage}</p>}{paused && <p>当前已暂停。请先通过宿主的暂停控制恢复，或结束本次会话。</p>}</div>
    </div>
    {error && <div className="xp-error" role="alert">{error}</div>}
    {blocked && <p className="xp-error">{typeof state.unavailableReason === 'string' ? state.unavailableReason : '当前环境尚未接入原生陪伴服务。'}</p>}
    <div className="xp-mode" role="group" aria-label="陪伴方式">{(['companion', 'focus', 'silent'] as const).map(value => <button type="button" key={value} aria-pressed={mode === value} disabled={blocked || pending.includes('presenceMode')} onClick={() => { if (running) void send('presenceMode', { mode: value }); else setConfig(current => ({ ...current, mode: value })) }}>{value === 'companion' ? '随时交流' : value === 'focus' ? '专注陪伴' : '静音陪伴'}</button>)}</div>
    <p className="xp-mode-note">{mode === 'focus' ? '减少主动插话，仍可随时与你交流。' : mode === 'silent' ? '保持文字回应，不播放声音。' : '会话全程可以互动，不必等到任务或对局结束。'}</p>
    {running && <form onSubmit={event => { event.preventDefault(); const text = message.trim(); if (!text) return; void send('presenceText', {text}); setMessage('') }}><label>和当前陪伴会话交流<input type="text" value={message} onChange={event => setMessage(event.target.value)} placeholder="告诉我你现在在做什么…" maxLength={8000}/></label><button type="submit" disabled={!message.trim() || paused || blocked}>发送到本次陪伴</button><small>本次临时对话随会话结束清除。要留下当前事件，请点击保存。</small></form>}
    <div className="xp-actions">
      <button type="button" disabled={!running || blocked || pending.includes('presenceInterrupt')} onClick={() => void send('presenceInterrupt')}>打断当前回应</button>
      <button type="button" disabled={!running || paused || blocked || pending.includes('presenceListen')} onClick={() => void send('presenceListen')}>听我说一次</button>
      {muted === undefined ? <button type="button" disabled={!running || blocked || pending.includes('presenceMute')} onClick={() => void send('presenceMute', { muted: true })}>关闭语音输出</button> : <button type="button" aria-pressed={muted} disabled={!running || blocked || pending.includes('presenceMute')} onClick={() => void send('presenceMute', { muted: !muted })}>{muted ? '开启语音输出' : '关闭语音输出'}</button>}
      <button className="xp-stop" type="button" disabled={(!running && !busyStart) || pending.includes('presenceStop')} onClick={() => void send('presenceStop')}>结束陪伴</button>
      {running && <button type="button" disabled={!state.canSaveEvent || blocked || pending.includes('presenceSave')} onClick={() => { if (confirm('保存当前已回应事件及其实际画面依据到本机长期资料和记忆？')) void send('presenceSave') }}>保存当前陪伴事件</button>}
    </div>
    <form onSubmit={start}>
      <details open={!running}><summary>本次陪伴设置</summary>
        <fieldset disabled={running || busyStart || blocked}>
          <label className="xp-check"><input type="checkbox" checked={observeScreen} onChange={event => setObserveScreen(event.target.checked)}/>读取选定应用画面</label>
          {observeScreen && <><button type="button" onClick={() => { void onCommand('launchableApps').then(value => { if (Array.isArray(value)) setApps(value as typeof apps) }).catch(cause => setError(String(cause))) }}>从手机应用中选择</button>{apps.length > 0 && <label>手机应用<select value={config.package} onChange={event => setConfig(current => ({...current,package:event.target.value}))}><option value="">请选择</option>{apps.map(app => <option key={app.package} value={app.package}>{app.name}</option>)}</select></label>}<label>仅允许观察此应用<input type="text" value={config.package} onChange={event => setConfig(current => ({ ...current, package: event.target.value }))} placeholder="com.example.game" autoCapitalize="none" autoCorrect="off" spellCheck={false} maxLength={200}/><small>切出此应用即暂停截图，3 分钟未返回结束会话。不授予操作权限。</small></label><label>DSH 授权密码（未设置可留空）<input type="password" autoComplete="off" value={config.password || ''} onChange={event => setConfig(current => ({...current,password:event.target.value}))}/></label></>}
          <div className="xp-grid"><label>本次时长（分钟）<input type="number" min={1} max={180} step={1} value={config.minutes} onChange={event => setConfig(current => ({ ...current, minutes: Number(event.target.value) }))}/></label><label>观察间隔（秒）<input type="number" min={5} max={120} step={1} disabled={!observeScreen} value={config.intervalSeconds} onChange={event => setConfig(current => ({ ...current, intervalSeconds: Number(event.target.value) }))}/></label></div>
          <label className="xp-check"><input type="checkbox" checked={config.voiceInput} onChange={event => setConfig(current => ({ ...current, voiceInput: event.target.checked }))}/>允许本次持续听取语音</label>
          <label>自动回应时限（秒）<input type="number" min={1} max={30} value={(config.responseDeadlineMs ?? 8000) / 1000} onChange={event => setConfig(current => ({...current,responseDeadlineMs:Number(event.target.value) * 1000}))}/><small>超过时限丢弃过时反应；实际延迟取决于手机和模型，尚未保证 3 秒响应。</small></label>
          <label className="xp-check"><input type="checkbox" checked={config.voiceOutput} onChange={event => setConfig(current => ({ ...current, voiceOutput: event.target.checked }))}/>播放角色声音</label>
          <small>麦克风只在会话开启并获系统授权后使用。静音模式会关闭声音输出。</small>
          <details><summary>语音路径与打断</summary><p className="xp-mode-note">默认离线系统语音。扬声器播放时暂停收音，可点击打断；耳机抢话需实际输出到耳机，设备兼容性仍需验证。</p><label className="xp-check"><input type="checkbox" checked={config.headphoneBargeIn || false} onChange={event => setConfig(current => ({...current,headphoneBargeIn:event.target.checked}))}/>耳机输出时允许免按键抢话</label><label className="xp-check"><input type="checkbox" checked={config.allowNetworkAsr || false} onChange={event => setConfig(current => ({...current,allowNetworkAsr:event.target.checked}))}/>允许系统语音识别服务联网处理本次语音</label><label className="xp-check"><input type="checkbox" checked={config.allowNetworkTts || false} onChange={event => setConfig(current => ({...current,allowNetworkTts:event.target.checked}))}/>允许系统声音服务联网合成回复</label><small>这两项会把语音或回复交给手机配置的系统服务，与 Gemini 中转是不同的数据去向。</small></details>
        </fieldset>
        {running && <p className="xp-mode-note">结束本次会话后可修改感知范围与时长。</p>}
      </details>
      {!running && <button className="xp-start" type="submit" disabled={busyStart || blocked}>{busyStart ? '等待开启结果…' : '开启本次陪伴'}</button>}
    </form>
    <details><summary>角色显示</summary><label className="xp-check"><input type="checkbox" checked={showCharacter} onChange={event => setShowCharacter(event.target.checked)}/>显示角色动画</label><label>本地素材格式<select value={layout} onChange={event => setLayout(event.target.value as ArtworkLayout)}><option value="sprite-4x6">动作图集 · 4 列 × 6 行</option><option value="portrait">单张角色图 · 静态</option></select></label><label className="xp-import">导入你有权使用的 PNG<input type="file" accept="image/png,.png" onChange={event => { const file = event.target.files?.[0]; if (file) void importArtwork(file); event.target.value = '' }}/></label><p className="xp-mode-note">安卓端保存在本机并用于悬浮角色，可恢复内置角色删除；浏览器预览只在本页。不会上传或改变人格。图集各行依次为待机、聆听、思考、说话、暂停、错误，每行 4 帧。说话图集是示意动画。</p><button type="button" onClick={resetArtwork}>恢复内置动态角色</button></details>
  </section>
}
