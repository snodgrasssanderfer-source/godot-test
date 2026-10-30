import { useEffect, useState } from 'react'
import { nativeCompanion, type Run, type Snapshot } from './bridge'

/** Floating overlay session: optionally reads one app's screen, for a bounded time. */
export function OverlaySession({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const [apps, setApps] = useState<{package: string; name: string}[]>([]), [pkg, setPkg] = useState(''), [minutes, setMinutes] = useState(15), [password, setPassword] = useState('')
  const running = snapshot.overlaySession?.running === true
  useEffect(() => { if (nativeCompanion) void run('launchableApps').then(v => { if (Array.isArray(v)) setApps(v as typeof apps) }).catch(() => undefined) }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return <div className="xk-stack">
    <div className="xk-grid">
      <label>看哪个应用<select value={pkg} onChange={e => setPkg(e.target.value)}><option value="">不看画面，只聊天</option>{apps.map(a => <option key={a.package} value={a.package}>{a.name}</option>)}</select></label>
      <label>多久<select value={minutes} onChange={e => setMinutes(Number(e.target.value))}>{[5, 15, 30, 60].map(n => <option key={n} value={n}>{n} 分钟</option>)}</select></label>
    </div>
    {pkg && <label>设备控制密码<input type="password" value={password} placeholder="设过才需要填" onChange={e => setPassword(e.target.value)} autoComplete="current-password"/></label>}
    <div className="xk-actions">
      <button className="xk-btn primary" disabled={!nativeCompanion} onClick={() => void run('startOverlay', {package: pkg, minutes, password}).then(() => setPassword('')).catch(() => undefined)}>{running ? '重新开始' : '开始'}</button>
      <button className="xk-btn" disabled={!running} onClick={() => void run('stopOverlay').catch(() => undefined)}>停止</button>
    </div>
  </div>
}

/** Android notifications may not carry the account number, so the owner names the logged-in QQ account. */
export function QqNotificationLabel({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const [namespace, setNamespace] = useState(snapshot.settings.qqAccountLabel ?? '')
  return <div className="xk-inline-form">
    <label>这台手机登录的 QQ 叫什么<input value={namespace} onChange={e => setNamespace(e.target.value)} maxLength={80} placeholder="换号时记得改"/></label>
    <button className="xk-btn" onClick={() => void run('settings', {...snapshot.settings, qqAccountLabel: namespace}).catch(() => undefined)}>保存</button>
  </div>
}

/** NapCat on the owner's server: address, token, the owner's QQ and the groups she may talk in. Her own QQ account is read from the bridge. */
export function QqConnection({snapshot, run, bare = false}: {snapshot: Snapshot; run: Run; bare?: boolean}) {
  const s = snapshot.settings, bridge = snapshot.messageBridge
  const [url, setUrl] = useState(s.oneBotUrl ?? ''), [token, setToken] = useState(''), [owner, setOwner] = useState(s.ownerQqId ?? ''), [groups, setGroups] = useState((s.qqGroups ?? []).join(' '))
  const [note, setNote] = useState(''), [frequency, setFrequency] = useState(String(s.groupReplyFrequency ?? 0.5))
  async function save() {
    try {
      await run('configureQq', {url: url.trim(), token: token.trim(), ownerQqId: owner.trim(), groups: groups.split(/[\s,，、]+/).filter(Boolean), groupReplyFrequency: Number(frequency)})
      setToken(''); setNote('已保存，正在连接…')
    } catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
  }
  const body = <>
    <div className="xk-card-head"><h2>QQ（NapCat）</h2><span className={'xk-pill' + (bridge?.connected ? ' ok' : '')}>{bridge?.connected ? '已连接' : bridge?.status ?? '未连接'}</span></div>
    <label>WebSocket 地址<input type="url" value={url} onChange={e => setUrl(e.target.value)} placeholder="wss://服务器:端口" autoComplete="off" autoCapitalize="none" spellCheck={false}/></label>
    <label>Token<input type="password" value={token} onChange={e => setToken(e.target.value)} autoComplete="new-password" placeholder={bridge?.tokenConfigured ? '已保存，留空不变' : 'NapCat 里设的访问令牌'}/></label>
    <label>你的 QQ 号<input value={owner} onChange={e => setOwner(e.target.value)} inputMode="numeric" placeholder="她只认这个号是你"/></label>
    <label>她能说话的群<textarea value={groups} onChange={e => setGroups(e.target.value)} rows={2} placeholder="群号，空格或逗号隔开"/></label>
    <label>在群里话多不多<select value={frequency} onChange={e => setFrequency(e.target.value)}><option value="0.3">少一点</option><option value="0.5">适中</option><option value="0.8">多一点</option></select></label>
    {bridge?.account && <p className="xk-hint">她的 QQ：{bridge.account}</p>}
    {bridge?.untrustedCertificate && <div className="xk-notice-card"><p>第一次连这台服务器。在服务器上运行 <code>openssl x509 -noout -fingerprint -sha256 -in 证书文件</code>，确认和下面一致：</p><code className="xk-code">{bridge.untrustedCertificate}</code><button className="xk-btn" onClick={() => void run('trustBridgeCert', {fingerprint: bridge.untrustedCertificate}).catch(() => undefined)}>一致，信任</button></div>}
    <div className="xk-actions"><button className="xk-btn primary" disabled={!nativeCompanion || !url.trim()} onClick={() => void save()}>保存并连接</button><button className="xk-btn ghost" disabled={!nativeCompanion} onClick={() => void run('disconnectBridge').catch(() => undefined)}>断开</button></div>
    {note && <p role="status" className="xk-hint">{note}</p>}
  </>
  return bare ? <div className="xk-stack-tight">{body}</div> : <div className="xk-card">{body}</div>
}
