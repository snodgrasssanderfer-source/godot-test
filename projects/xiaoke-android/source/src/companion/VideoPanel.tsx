import { useRef, useState } from 'react'
import { UploadSimple } from '@phosphor-icons/react'
import { nativeCompanion } from './bridge'

type Run = (action: string, data?: object) => Promise<unknown>
function seconds(value: string): number {
  if (!value.trim()) return 0
  const match = /^(?:(\d+):)?(\d{1,5})(?:\.(\d{1,3}))?$/.exec(value.trim())
  if (!match || (match[1] && Number(match[2]) >= 60)) throw new Error('时间请填 分:秒，例如 05:00；也可填总秒数。')
  return Number(match[1] ?? 0) * 60 + Number(match[2]) + Number(`0.${match[3] ?? 0}`)
}

export function VideoPanel({run, networkEnabled}: {run: Run; networkEnabled: boolean}) {
  const [videoId, setVideoId] = useState(''), [start, setStart] = useState('00:00'), [end, setEnd] = useState('')
  const [samples, setSamples] = useState(3), [busy, setBusy] = useState(false), [result, setResult] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)
  const valid = /^BV[0-9A-Za-z]{10}$/.test(videoId)
  async function act(action: string, extra: object = {}) {
    if (busy) return
    try {
      const startSeconds = seconds(start), endSeconds = seconds(end)
      if (endSeconds > 0 && endSeconds <= startSeconds) throw new Error('结束时间需晚于开始时间。')
      setBusy(true)
      const value = await run(action, {videoId, startSeconds, endSeconds, samples, ...extra})
      setResult(JSON.stringify(value, null, 2))
    } catch (e) { setResult(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }
  async function importFile(file: File) {
    if (file.size > 12 * 1024 * 1024) { setResult('整段附件最大 12 MB，长视频用“本地视频取关键帧”。'); return }
    try {
      const base64 = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader(); reader.onload = () => { if (typeof reader.result === 'string') resolve(reader.result.split(',')[1]); else reject(new Error('文件读取格式不正确')) }; reader.onerror = () => reject(new Error('文件读取失败')); reader.readAsDataURL(file)
      })
      await act('importMedia', {name: file.name, mime: file.type, base64})
    } catch (e) { setResult(String(e)) }
  }
  return <div className="xk-stack-tight">
    <label>BV 号<input value={videoId} onChange={e => setVideoId(e.target.value.trim())} placeholder="BV…" autoCapitalize="none" spellCheck={false}/></label>
    <div className="xk-grid"><label>从<input value={start} onChange={e => setStart(e.target.value)} placeholder="05:00"/></label>
      <label>到<input value={end} onChange={e => setEnd(e.target.value)} placeholder="留空到结尾"/></label></div>
    <label>关键帧<input type="number" min={3} max={12} value={samples} onChange={e => setSamples(Number(e.target.value))}/></label>
    <div className="xk-actions">
      <button className="xk-btn primary" disabled={busy || !valid || !networkEnabled || !nativeCompanion} onClick={() => void act('observePublicVideo')}>截这一段给她</button>
      <button className="xk-btn" disabled={busy || !valid || !networkEnabled || !nativeCompanion} onClick={() => void act('videoInfo')}>只读字幕</button>
      <button className="xk-btn" disabled={busy || !valid || !nativeCompanion} onClick={() => void act('openApp', {package: 'tv.danmaku.bili'})}>在B站打开</button>
    </div>
    <details className="xk-more"><summary>其他方式</summary><div className="xk-actions">
      <button className="xk-btn" disabled={busy || !nativeCompanion} onClick={() => void act('pickVideoFrames')}>本地视频取关键帧</button>
      <button className="xk-btn" disabled={busy || !valid || !nativeCompanion} onClick={() => void act('combineVideoEvidence')}>合并字幕和关键帧</button>
      <button className="xk-btn" disabled={busy || !valid || !nativeCompanion} onClick={() => { if (confirm('在B站前台截 6 张正在播放的画面（没有声音）？')) void act('captureVideo') }}>截正在播的画面</button>
      <button className="xk-btn" disabled={busy || !nativeCompanion} onClick={() => fileInput.current?.click()}><UploadSimple size={18} weight="duotone"/> 导入文件（12 MB 内）</button>
    </div><input ref={fileInput} type="file" hidden accept="video/mp4,video/webm,audio/mpeg,audio/mp4,audio/wav,image/jpeg,image/png,application/pdf" onChange={e => {const file = e.target.files?.[0]; if (file) void importFile(file); e.target.value = ''}}/></details>
    {busy && <p role="status">正在取…</p>}
    {result && <details className="xk-more" open><summary>结果</summary><pre>{result}</pre></details>}
  </div>
}
