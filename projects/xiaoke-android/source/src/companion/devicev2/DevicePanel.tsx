import { useState } from 'react'
import type { ModulePanelProps } from '../iteration/contracts'
import './device.css'

type RecordValue = Record<string, unknown>
const record = (value: unknown): RecordValue => value && typeof value === 'object' ? value as RecordValue : {}
const string = (value: unknown) => typeof value === 'string' ? value : ''
const number = (value: unknown) => typeof value === 'number' ? value : 0

export function DevicePanel({ state, run, available }: ModulePanelProps) {
  const capture = record(state.capture), control = record(state.control), audio = record(state.audio), overlay = record(state.overlay)
  const analysis = record(audio.analysis), tracks = record(audio.tracks)
  const [packageName, setPackageName] = useState('')
  const [microphone, setMicrophone] = useState(true)
  const [playback, setPlayback] = useState(true)
  const [singlePlayer, setSinglePlayer] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [point, setPoint] = useState<{ x: number; y: number }>()
  const [prompt, setPrompt] = useState('听听这段，和我聊聊。')
  const perform = async (action: string, data: RecordValue = {}) => {
    setError(''); setBusy(true)
    try { return await run(action, data) }
    catch (e) { setError(e instanceof Error ? e.message : '操作没有完成'); return undefined }
    finally { setBusy(false) }
  }
  const startAudio = async () => {
    setError(''); setBusy(true)
    try {
      await run('audio.configure', { microphone, playback, durationSeconds: 20, packageName })
      await run('audio.start', {})
    } catch (e) { setError(e instanceof Error ? e.message : '未能开始收音') }
    finally { setBusy(false) }
  }
  const stopInput = () => { void run('control.stop', {}).catch(e => setError(e instanceof Error ? e.message : '停止输入失败')) }
  const tap = () => {
    if (!point) return
    void perform('control.tap', { requestId: crypto.randomUUID(), sessionId: control.sessionId, inputEpoch: control.inputEpoch,
      frameId: capture.frameId, ...point })
  }
  const recording = ['awaiting_permission', 'starting', 'recording', 'stopping'].includes(string(audio.state))
  const canRead = !recording && Object.keys(tracks).length > 0 && !!audio.captureSession
  return <section className="device-panel" aria-label="手机陪伴与操作">
    {!available && <p role="status" className="xk-hint">浏览器没有连接手机。</p>}
    {error && <p className="device-error" role="alert">{error}</p>}
    <div className="device-actions">
      <button type="button" disabled={!available} className="device-stop" onClick={stopInput}>仅停止输入</button>
      <button type="button" disabled={!available} onClick={() => void perform('speech.interrupt')}>打断当前语音回应</button>
      <button type="button" disabled={!available} onClick={() => void perform('overlay.visibility', { visible: !overlay.visible })}>{overlay.visible ? '隐藏浮球' : '显示浮球'}</button>
    </div>
    <p className="device-note">浮球上按住五秒也能急停。</p>
    <fieldset disabled={!available || busy}>
      <legend>看画面与操作</legend>
      <label>目标应用包名<input value={packageName} onChange={e => setPackageName(e.target.value.trim())} placeholder="例如 com.example.game" autoCapitalize="none" /></label>
      <label className="device-check"><input type="checkbox" checked={singlePlayer} onChange={e => setSinglePlayer(e.target.checked)} />这是单机游戏，允许她在里面操作</label>
      <div className="device-actions">
        <button type="button" disabled={!packageName} onClick={() => void perform('capture.request', { packageName })}>打开应用并观察一帧</button>
        <button type="button" disabled={!packageName || !singlePlayer || Boolean(control.releasePending)} onClick={() => void perform(control.state === 'stopped' ? 'control.resume' : 'control.start', { packageName, singlePlayer, durationSeconds: 120, expectedInputEpoch: number(control.inputEpoch) })}>让她操作两分钟</button>
        <button type="button" onClick={() => void perform('capture.stop')}>不看了</button>
      </div>
      <p role="status">操作：{control.state === 'authorized' ? '进行中' : '停着'}{control.releasePending ? ' · 等手指松开' : ''} · 画面：{string(capture.state) || '没开'}</p>
      {capture.preview && typeof capture.preview === 'string' ? <div>
        <p className="device-note">点画面选位置，再确认点击；画面超过五秒要重新看。</p>
        <button type="button" className="device-frame" aria-label="选择游戏点击位置" onClick={e => {
          const rect = e.currentTarget.getBoundingClientRect()
          setPoint({ x: Math.round(number(capture.left) + (e.clientX - rect.left) / rect.width * number(capture.width)),
            y: Math.round(number(capture.top) + (e.clientY - rect.top) / rect.height * number(capture.height)) })
        }}><img src={capture.preview} alt="当前画面" /></button>
        <p>{point ? `选了 ${point.x}, ${point.y}` : '还没选位置'}</p>
        <button type="button" disabled={!point || control.state !== 'authorized' || Boolean(control.releasePending)} onClick={tap}>点这里</button>
      </div> : null}
      {capture.error ? <p role="alert">{string(capture.error)}</p> : null}
    </fieldset>
    <fieldset disabled={!available || busy}>
      <legend>听声音</legend>
      <div className="device-actions">
        <label className="device-check"><input type="checkbox" checked={microphone} onChange={e => setMicrophone(e.target.checked)} />麦克风</label>
        <label className="device-check"><input type="checkbox" checked={playback} onChange={e => setPlayback(e.target.checked)} />应用里的声音</label>
      </div>
      <button type="button" disabled={recording || (!microphone && !playback) || (playback && !packageName)} onClick={() => void startAudio()}>系统授权后采集二十秒</button>
      <button type="button" disabled={!recording} onClick={() => void perform('audio.stop', audio.captureSession ? { captureSession: audio.captureSession } : {})}>停止收音</button>
      <p role="status">{string(audio.detail) || '还没开始'}</p>
      {Object.entries(tracks).map(([source, value]) => { const track = record(value); return <p key={source}>{source === 'mic' ? '麦克风' : '内播'}：{(number(track.samples) / 16000).toFixed(1)} 秒 · {track.hasSignal ? '有声音' : '没听到声音'}</p> })}
      <label>想让她听什么<input value={prompt} onChange={e => setPrompt(e.target.value)} maxLength={4000} /></label>
      <button type="button" disabled={!canRead || ['queued', 'streaming'].includes(string(analysis.state))} onClick={() => void perform('audio.analyze', { captureSession: audio.captureSession, prompt })}>让她听听</button>
      <button type="button" disabled={!['queued', 'streaming'].includes(string(analysis.state))} onClick={() => void perform('audio.cancelAnalysis')}>别听了</button>
      {analysis.text ? <p className="device-answer">{string(analysis.text)}</p> : null}
      {analysis.error ? <p role="alert">{string(analysis.error)}</p> : null}
    </fieldset>
  </section>
}
