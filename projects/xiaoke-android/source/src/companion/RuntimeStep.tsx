import { useCallback, useEffect, useRef, useState } from 'react'
import { DownloadSimple } from '@phosphor-icons/react'
import { runtimeBridge } from '../platform/native'
import type { RuntimeState } from '../platform/types'

const WORKING = ['preparing', 'downloading', 'verifying', 'extracting', 'stopping']

/** DSH's Ubuntu, installed from the copy inside the APK (checked against its manifest). Claude Code runs in it. */
export function RuntimeStep() {
  const [state, setState] = useState<RuntimeState | null>(null), [error, setError] = useState('')
  const timer = useRef<number>()
  const refresh = useCallback(async () => { try { setState(await runtimeBridge.getState()) } catch (e) { setError(e instanceof Error ? e.message : String(e)) } }, [])
  useEffect(() => { void refresh(); return () => window.clearTimeout(timer.current) }, [refresh])
  useEffect(() => { if (state && WORKING.includes(state.phase)) { timer.current = window.setTimeout(() => void refresh(), 1000); return () => window.clearTimeout(timer.current) } }, [state, refresh])
  async function install() {
    setError('')
    // Empty source = the bundled, digest-checked rootfs; no download.
    try { const pending = runtimeBridge.install({manifestUrl: '', manifestSha256: ''}); window.setTimeout(() => void refresh(), 500); await pending } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    void refresh()
  }
  if (!state) return <div className="xk-card"><p className="xk-hint">正在查看运行环境…</p></div>
  const ready = Boolean(state.installedVersion) && !WORKING.includes(state.phase)
  const pct = state.totalBytes ? Math.round(state.downloadedBytes / state.totalBytes * 100) : null
  return <div className="xk-card">
    <div className="xk-card-head"><h2>Ubuntu 运行环境</h2><span className="xk-pill">{ready ? `v${state.installedVersion}` : '未安装'}</span></div>
    {ready ? <p className="xk-hint">装好了。</p> : <>
      <p className="xk-hint">从 App 里自带的镜像展开（约 1.3 GB），不用下载。</p>
      {WORKING.includes(state.phase) && <div className="xk-usage"><span>{({preparing: '准备', downloading: '读取', verifying: '校验', extracting: '展开', stopping: '停止'} as Record<string, string>)[state.phase]}中{pct !== null && <b>{pct}%</b>}</span><span className="xk-meter"><span style={{width: `${pct ?? 30}%`}}/></span></div>}
      {!WORKING.includes(state.phase) && <div className="xk-actions"><button className="xk-btn primary" onClick={() => void install()}><DownloadSimple size={18} weight="duotone"/> 安装</button></div>}
    </>}
    {(error || state.errorCode) && <p className="xk-hint xk-warn">{error || state.errorCode}</p>}
  </div>
}
