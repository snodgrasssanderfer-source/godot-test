import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowSquareOut, Check, DownloadSimple, PaperPlaneRight, ShieldCheck, SignIn, SignOut, X } from '@phosphor-icons/react'
import type { ClaudePermission, ClaudeSettings, ClaudeStatus, Run } from './bridge'

const MODELS: [string, string][] = [['default', '跟随 Claude Code 默认'], ['opus', 'Opus'], ['sonnet', 'Sonnet'], ['haiku', 'Haiku']]
const EFFORTS: [ClaudeSettings['effort'], string][] = [['low', '快'], ['medium', '平衡'], ['high', '深'], ['xhigh', '更深'], ['max', '最深']]

/** Claude Code's questions about editing files or running commands. Shown in chat and here; also on the ball and as a notification. */
export function ClaudePermissions({items, run}: {items: ClaudePermission[]; run: Run}) {
  if (!items.length) return null
  return <div className="xk-stack">{items.map(p => <div key={p.id} className="xk-card xk-permission" role="alert">
    <div className="xk-card-head"><h3><ShieldCheck size={18} weight="duotone"/> 她想{p.summary.split('：')[0]}</h3></div>
    <pre>{p.summary.split('：').slice(1).join('：')}</pre>
    <div className="xk-actions">
      <button className="xk-btn primary small" onClick={() => void run('claudePermission', {id: p.id, allow: true}).catch(() => undefined)}><Check size={16} weight="bold"/> 允许这一次</button>
      <button className="xk-btn ghost small" onClick={() => void run('claudePermission', {id: p.id, allow: false}).catch(() => undefined)}><X size={16} weight="bold"/> 拒绝</button>
    </div>
  </div>)}</div>
}

function Usage({label, window}: {label: string; window?: {utilization: number; resetsAt: number}}) {
  if (!window) return null
  const pct = Math.round(Math.min(1, Math.max(0, window.utilization)) * 100)
  const reset = window.resetsAt ? new Date(window.resetsAt * 1000).toLocaleString('zh-CN', {month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'}) : ''
  return <div className="xk-usage"><span>{label}<b>{pct}%</b></span><span className="xk-meter"><span style={{width: `${pct}%`}}/></span>{reset && <small>{reset} 重置</small>}</div>
}

/** [only] narrows it to one onboarding step: downloading Claude Code, or logging in. */
export function ClaudePanel({run, only}: {run: Run; only?: 'install' | 'login'}) {
  const compact = only !== undefined
  const [status, setStatus] = useState<ClaudeStatus | null>(null), [code, setCode] = useState(''), [saved, setSaved] = useState(false)
  const [form, setForm] = useState<ClaudeSettings | null>(null)
  const timer = useRef<number>()
  // The latest run in a ref: refresh stays stable, so the first effect asks for login status (a PRoot start) only once.
  const runRef = useRef(run)
  runRef.current = run
  const refresh = useCallback(async (auth = false) => {
    try { const s = await runRef.current('claudeStatus', {refresh: auth}) as ClaudeStatus; setStatus(s); setForm(f => f ?? s.settings) } catch { /* toast */ }
  }, [])
  useEffect(() => { void refresh(true); return () => window.clearTimeout(timer.current) }, [refresh])
  // Poll only while something is moving: a download, a login, or questions waiting.
  const busy = Boolean(status && (['checking', 'downloading', 'verifying'].includes(status.install.phase) || status.login.running || status.permissions.length > 0 || status.workspace.job?.phase === 'running'))
  useEffect(() => { if (busy) { timer.current = window.setTimeout(() => void refresh(), 1500); return () => window.clearTimeout(timer.current) } }, [status, busy, refresh])
  if (!status) return <div className="xk-card"><p className="xk-hint">正在查看 Claude Code…</p></div>
  const i = status.install
  const progress = i.phase === 'downloading' && i.total ? Math.round((i.done ?? 0) / i.total * 100) : null
  const save = (next: ClaudeSettings) => { setForm(next); setSaved(false); void run('claudeSettings', next).then(() => { setSaved(true); void refresh() }).catch(() => undefined) }

  return <div className="xk-stack">
    <div className="xk-card">
      <div className="xk-card-head"><h2>Claude Code</h2><span className="xk-pill">{status.version ? `v${status.version}` : '未安装'}</span></div>
      {!status.runtimeReady && <p className="xk-hint">先装好 Ubuntu 运行环境（DSH 工作台第一次打开时会装），Claude Code 跑在里面。</p>}
      {!status.version && only === 'login' && <p className="xk-hint">先在上一步装好 Claude Code。</p>}
      {!status.version && status.runtimeReady && only !== 'login' && <>
        <p className="xk-hint">从 Anthropic 官方地址下载原版（约 280 MB），按官方签名清单核对 sha256，对不上就删掉不用。</p>
        {progress !== null && <div className="xk-usage"><span>下载中<b>{progress}%</b></span><span className="xk-meter"><span style={{width: `${progress}%`}}/></span></div>}
        {i.phase === 'verifying' && <p className="xk-hint">校验通过，正在试运行…</p>}
        {i.phase === 'failed' && <p className="xk-hint xk-warn">{i.error}</p>}
        <div className="xk-actions">
          {['checking', 'downloading', 'verifying'].includes(i.phase)
            ? <button className="xk-btn ghost" onClick={() => void run('claudeCancelInstall').then(() => refresh()).catch(() => undefined)}>暂停下载</button>
            : <button className="xk-btn primary" onClick={() => void run('claudeInstall').then(() => refresh()).catch(() => undefined)}><DownloadSimple size={18} weight="duotone"/> {i.phase === 'failed' ? '重试（会接着下）' : '下载并校验'}</button>}
        </div>
      </>}
      {status.version && only === 'install' && <p className="xk-hint">已装好并校验过，能运行。</p>}
      {status.version && only !== 'install' && <>
        <p className="xk-hint">{status.loggedIn ? `已登录${status.authMethod ? ` · ${status.authMethod}` : ''}${status.authNote ? ` · ${status.authNote}` : ''}` : '还没登录。用你自己的 Claude 订阅登录；登录由 Claude Code 自己完成，App 不碰账号和令牌。'}</p>
        {!status.loggedIn && !status.login.running && <div className="xk-actions">
          <button className="xk-btn primary" onClick={() => void run('claudeLogin', {method: 'claudeai'}).then(() => refresh()).catch(() => undefined)}><SignIn size={18} weight="duotone"/> 用订阅登录</button>
        </div>}
        {status.login.running && status.login.checking && <p className="xk-hint">登录结束了，正在确认…</p>}
        {status.login.running && !status.login.checking && <>
          {status.login.url && <a className="xk-btn primary" href={status.login.url} target="_blank" rel="noopener noreferrer"><ArrowSquareOut size={18} weight="duotone"/> 打开登录页</a>}
          <p className="xk-hint">在浏览器里授权后，把页面给出的代码贴到这里。</p>
          <form className="xk-composer-row" onSubmit={e => { e.preventDefault(); if (code.trim()) void run('claudeLoginInput', {text: code.trim()}).then(() => { setCode(''); void refresh() }).catch(() => undefined) }}>
            <input value={code} onChange={e => setCode(e.target.value)} placeholder="授权代码" autoCapitalize="none" spellCheck={false} autoComplete="off"/>
            <button className="xk-btn primary small" type="submit" aria-label="发送代码"><PaperPlaneRight size={18} weight="fill"/></button>
          </form>
          {!status.login.url && <p className="xk-hint">等 Claude Code 给出登录链接…</p>}
          {status.login.output && (status.login.url
            ? !compact && <details className="xk-more"><summary>Claude Code 的输出</summary><pre className="xk-log">{status.login.output}</pre></details>
            : <pre className="xk-log">{status.login.output}</pre>)}
          <button className="xk-btn ghost small" onClick={() => void run('claudeLoginStop').then(() => refresh()).catch(() => undefined)}>取消登录</button>
        </>}
        {!status.login.running && status.login.exit != null && !status.loggedIn && <>
          <p className="xk-hint xk-warn">登录没成功（退出码 {status.login.exit}）。下面是 Claude Code 最后说的话，可以再试一次；还不行就截图给我。</p>
          {status.login.output && <pre className="xk-log">{status.login.output.slice(-1500)}</pre>}
          {status.authNote && <pre className="xk-log">{status.authNote}</pre>}
        </>}
      </>}
    </div>

    {!compact && status.version && form && <div className="xk-card">
      <h2>她什么时候用 Claude</h2>
      <label className="xk-switch-row"><span>和你私聊、你交代的事用 Claude<small>QQ 群在 连接 → QQ 里逐个打开，默认 Gemini</small></span><input type="checkbox" checked={form.enabled} onChange={e => save({...form, enabled: e.target.checked})}/></label>
      <label>模型<select value={MODELS.some(m => m[0] === form.model) ? form.model : 'custom'} onChange={e => save({...form, model: e.target.value === 'custom' ? form.model : e.target.value})}>
        {MODELS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}<option value="custom">其他（填写）</option></select></label>
      {!MODELS.some(m => m[0] === form.model) && <label>模型名<input value={form.model} onChange={e => setForm({...form, model: e.target.value})} onBlur={() => save(form)} autoCapitalize="none" spellCheck={false}/></label>}
      <label className="xk-switch-row"><span>完全访问<small>{form.fullAccess ? '你的私聊里改文件、跑命令不再逐条问你；群里始终没有这些能力' : '关着时，她改文件、跑命令前都会先问你'}</small></span>
        <input type="checkbox" checked={Boolean(form.fullAccess)} onChange={e => { if (!e.target.checked || confirm('打开后，她在你的私聊里改文件、跑命令都不再问你（群里不受影响）。确定？')) save({...form, fullAccess: e.target.checked}) }}/></label>
      <label>思考强度<select value={form.effort} onChange={e => save({...form, effort: e.target.value as ClaudeSettings['effort']})}>{EFFORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
      {saved && <p className="xk-hint">已保存，从下一条消息起生效。</p>}
      {status.outage && <p className="xk-hint xk-warn">现在先用着 Gemini：{status.outage.detail || status.outage.reason}{status.outage.until < 8e15 ? `，${new Date(status.outage.until).toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'})} 后再试 Claude` : ''}。</p>}
    </div>}

    {!compact && status.version && <div className="xk-card">
      <h2>代码工作区</h2>
      <p className="xk-hint">{status.workspace.exists ? `${status.workspace.path}${status.workspace.branch ? ` · 当前分支 ${status.workspace.branch}` : ''}` : '把这个项目的公开仓库克隆到手机里。你在私聊里让她查 bug、改代码时，她在这里读、改，提交到新分支再告诉你改了什么。'}</p>
      <p className="xk-hint">签名和发版只在云端做，私钥不会到手机上。推送需要你自己在 DSH 终端里配好 git 凭据；没配时她只提交在本地。</p>
      {status.workspace.job?.phase === 'running' && <p className="xk-hint">正在准备…</p>}
      {status.workspace.job?.phase === 'failed' && <p className="xk-hint xk-warn">{status.workspace.job.error}</p>}
      <div className="xk-actions"><button className="xk-btn" disabled={status.workspace.job?.phase === 'running'} onClick={() => void run('claudeWorkspace').then(() => refresh()).catch(() => undefined)}>{status.workspace.exists ? '拉取最新' : '准备工作区'}</button></div>
    </div>}

    {!compact && status.version && <div className="xk-card">
      <h2>订阅用量</h2>
      {status.usage.fiveHour || status.usage.sevenDay ? <>
        <Usage label="5 小时" window={status.usage.fiveHour}/>
        <Usage label="本周" window={status.usage.sevenDay}/>
        <Usage label="本周 Opus" window={status.usage.sevenDayOpus}/>
        <small className="xk-hint">来自 Claude Code 自己的报告{status.usage.updatedAt ? `，${new Date(status.usage.updatedAt).toLocaleString('zh-CN')}` : ''}。</small>
      </> : <p className="xk-hint">Claude 回复过之后，这里显示 Claude Code 报告的用量。</p>}
    </div>}

    {!compact && <ClaudePermissions items={status.permissions} run={async (a, d) => { const r = await run(a, d); void refresh(); return r }}/>}

    {!compact && status.loggedIn && <div className="xk-actions"><button className="xk-btn ghost danger" onClick={() => { if (confirm('退出 Claude 登录？之后都会用 Gemini。')) void run('claudeLogout').then(() => refresh(true)).catch(() => undefined) }}><SignOut size={18} weight="duotone"/> 退出登录</button></div>}
  </div>
}
