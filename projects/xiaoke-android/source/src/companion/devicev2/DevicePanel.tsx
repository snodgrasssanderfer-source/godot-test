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
  const [prompt, setPrompt] = useState('结合这段声音和我聊聊，缺少的内容请说明。')
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
    <p>文字陪聊持续可用。这里只管理本次手机观察、声音片段和单机游戏输入。</p>
    {!available && <p role="status">设备功能需要安卓安装包；浏览器没有连接手机。</p>}
    {error && <p className="device-error" role="alert">{error}</p>}
    <div className="device-actions">
      <button type="button" disabled={!available} className="device-stop" onClick={stopInput}>仅停止输入</button>
      <button type="button" disabled={!available} onClick={() => void perform('speech.interrupt')}>打断当前语音回应</button>
      <button type="button" disabled={!available} onClick={() => void perform('overlay.visibility', { visible: !overlay.visible })}>{overlay.visible ? '隐藏浮球' : '显示浮球'}</button>
    </div>
    <p className="device-note">输入急停保留陪聊、观察与收音。浮球上静止按住五秒也可急停；普通权限不支持任意屏幕位置的五秒监听。</p>
    <fieldset disabled={!available || busy}>
      <legend>观察与单机游戏</legend>
      <label>目标应用包名<input value={packageName} onChange={e => setPackageName(e.target.value.trim())} placeholder="例如 com.example.game" autoCapitalize="none" /></label>
      <label className="device-check"><input type="checkbox" checked={singlePlayer} onChange={e => setSinglePlayer(e.target.checked)} />我确认这是本机单人游戏，并允许本次在该应用内操作</label>
      <div className="device-actions">
        <button type="button" disabled={!packageName} onClick={() => void perform('capture.request', { packageName })}>打开应用并观察一帧</button>
        <button type="button" disabled={!packageName || !singlePlayer || Boolean(control.releasePending)} onClick={() => void perform(control.state === 'stopped' ? 'control.resume' : 'control.start', { packageName, singlePlayer, durationSeconds: 120, expectedInputEpoch: number(control.inputEpoch) })}>主人授权操作两分钟</button>
        <button type="button" onClick={() => void perform('capture.stop')}>结束设备观察</button>
      </div>
      <p role="status">输入：{control.state === 'authorized' ? '已授权' : '已停止'}{control.releasePending ? ' · 等待系统确认触点释放' : ''}；画面：{string(capture.state) || '未开始'}</p>
      {capture.preview && typeof capture.preview === 'string' ? <div>
        <p>点画面选择位置，再确认点击。执行前会核对授权、窗口和画面时效；超过五秒需重新观察。也可在目标应用的浮球面板内刷新和操作。</p>
        <button type="button" className="device-frame" aria-label="选择游戏点击位置" onClick={e => {
          const rect = e.currentTarget.getBoundingClientRect()
          setPoint({ x: Math.round(number(capture.left) + (e.clientX - rect.left) / rect.width * number(capture.width)),
            y: Math.round(number(capture.top) + (e.clientY - rect.top) / rect.height * number(capture.height)) })
        }}><img src={capture.preview} alt="本次授权应用的临时画面" /></button>
        <p>{point ? `选点：${point.x}, ${point.y}` : '尚未选择位置'}</p>
        <button type="button" disabled={!point || control.state !== 'authorized' || Boolean(control.releasePending)} onClick={tap}>确认点击选定位置</button>
      </div> : null}
      {capture.error ? <p role="alert">{string(capture.error)}</p> : null}
      <p className="device-note">当前提供有界观察和手势基础能力；尚未完成具体游戏的真机验收，不代表通用 RPG 已可自主游玩。</p>
    </fieldset>
    <fieldset disabled={!available || busy}>
      <legend>内播与麦克风</legend>
      <div className="device-actions">
        <label className="device-check"><input type="checkbox" checked={microphone} onChange={e => setMicrophone(e.target.checked)} />麦克风</label>
        <label className="device-check"><input type="checkbox" checked={playback} onChange={e => setPlayback(e.target.checked)} />目标应用内播</label>
      </div>
      <button type="button" disabled={recording || (!microphone && !playback) || (playback && !packageName)} onClick={() => void startAudio()}>系统授权后采集二十秒</button>
      <button type="button" disabled={!recording} onClick={() => void perform('audio.stop', audio.captureSession ? { captureSession: audio.captureSession } : {})}>停止收音</button>
      <p role="status">{string(audio.detail) || '尚未开始收音'}</p>
      {Object.entries(tracks).map(([source, value]) => { const track = record(value); return <p key={source}>{source === 'mic' ? '麦克风' : '内播'}：{(number(track.samples) / 16000).toFixed(1)} 秒 · {track.hasSignal ? '收到信号' : '未检测到信号，可能静音或系统限制'}</p> })}
      <label>附加给本次对话的说明<input value={prompt} onChange={e => setPrompt(e.target.value)} maxLength={4000} /></label>
      <button type="button" disabled={!canRead || ['queued', 'streaming'].includes(string(analysis.state))} onClick={() => void perform('audio.analyze', { captureSession: audio.captureSession, prompt })}>将片段交给模型理解</button>
      <button type="button" disabled={!['queued', 'streaming'].includes(string(analysis.state))} onClick={() => void perform('audio.cancelAnalysis')}>取消这段音频分析</button>
      {analysis.text ? <p className="device-answer">{string(analysis.text)}</p> : null}
      {analysis.error ? <p role="alert">{string(analysis.error)}</p> : null}
      <p className="device-note">两路声音分开采集，最多三十秒并在内存临时保留。目标应用可以禁止内播捕获；麦克风可能含外放回声。采样期间暂停系统语音识别以免争用麦克风。当前仍是有界采样和轮流听说，没有验证全双工或说话人识别。</p>
    </fieldset>
  </section>
}
