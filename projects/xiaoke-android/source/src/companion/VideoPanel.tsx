import { useRef, useState } from 'react'
import { Upload, Video } from 'lucide-react'
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
    if (file.size > 12 * 1024 * 1024) { setResult('整段附件限 12 MB；较长视频请用“从本地视频取关键帧”，无需复制完整文件。'); return }
    try {
      const base64 = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(new Error('文件读取失败')); reader.readAsDataURL(file)
      })
      await act('importMedia', {name: file.name, mime: file.type, base64})
    } catch (e) { setResult(String(e)) }
  }
  return <div className="card"><h2><Video size={20}/> B站内容观察</h2>
    <p>先选片段，取得字幕与画面，再在对话中附加资料让模型理解。公开读取当前使用第 1 P；模型只读到的部分才算观察依据。</p>
    <label>视频 BV 号<input value={videoId} onChange={e => setVideoId(e.target.value.trim())} placeholder="BV…" /></label>
    <div className="form-grid"><label>开始时间<input value={start} onChange={e => setStart(e.target.value)} placeholder="05:00" /></label>
      <label>结束时间<input value={end} onChange={e => setEnd(e.target.value)} placeholder="留空到结尾，例如 12:00" /></label>
      <label>关键帧数量<input type="number" min={3} max={12} value={samples} onChange={e => setSamples(Number(e.target.value))} /></label></div>
    <div className="form-actions">
      <button disabled={busy || !valid || !nativeCompanion} onClick={() => void act('openApp', {package: 'tv.danmaku.bili'})}>打开视频</button>
      <button disabled={busy || !valid || !networkEnabled || !nativeCompanion} onClick={() => void act('videoInfo')}>读取此时段公开字幕</button>
      <button disabled={busy || !valid || !networkEnabled || !nativeCompanion} onClick={() => {if (confirm('从 B站公开接口读取此时段的字幕和关键帧，最多读取 48 MB 视频分段；不会登录、发送给模型或评论。继续？')) void act('observePublicVideo')}}>读取公开视频片段</button>
      <button disabled={busy || !nativeCompanion} onClick={() => void act('pickVideoFrames')}>从本地视频取关键帧</button>
      <button disabled={busy || !valid || !nativeCompanion} onClick={() => void act('combineVideoEvidence')}>合并此视频字幕与关键帧</button>
    </div>
    <small>本地取帧使用系统文件选择器，可读取较长视频；按请求时间提取邻近帧，不上传整片。与 BV 号的关联由你确认。字幕与画面起止时间一致时才能合并。</small>
    <details><summary>其他观察方式</summary><div className="form-actions">
      <button disabled={busy || !valid || !nativeCompanion} onClick={() => { if (confirm('将在 B站前台采集 6 张当前播放画面。此方式不能自动跳到上方时段，且没有音轨。继续？')) void act('captureVideo') }}>当前播放画面短时采样</button>
      <button disabled={busy || !nativeCompanion} onClick={() => fileInput.current?.click()}><Upload size={16}/> 导入整段附件（12 MB 内）</button>
    </div><input ref={fileInput} type="file" hidden accept="video/mp4,video/webm,audio/mpeg,audio/mp4,audio/wav,image/jpeg,image/png,application/pdf" onChange={e => {const file = e.target.files?.[0]; if (file) void importFile(file); e.target.value = ''}} />
      <small>整段视频或音频需要所选中转支持对应模态。只有图像能力时可使用字幕与关键帧路径；没有获得的音轨、画面和过程不能补写。</small></details>
    {busy && <p role="status">正在取得内容；不会自动发布评论。</p>}
    {result && <details open><summary>最近取证结果</summary><pre className="operation-result">{result}</pre></details>}
    <p className="subtle">资料保存到本机，直到你在对话中主动附加。评论会进入任务页，按视频、账号和正文单独确认。</p>
  </div>
}
