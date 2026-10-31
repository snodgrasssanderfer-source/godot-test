import { useState, type ReactNode } from 'react'
import { BatteryCharging, Bell, ChatsCircle, CheckCircle, DeviceMobile, Eye, FileText, GameController, HandPointing, PictureInPicture, Television, WarningCircle, Wrench, X } from '@phosphor-icons/react'
import { field, nativeCompanion, type Entry, type Run, type Snapshot } from './bridge'
import { KeepAlive } from './KeepAlive'
import { OverlaySession, QqConnection, QqNotificationLabel } from './Connections'
import { VideoPanel } from './VideoPanel'
import { GameSessionPanel } from './iteration/GameSessionPanel'
import { DevicePanel } from './devicev2/DevicePanel'
import { PageHeader, Section } from './ui'

export function ConnectPage({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const [status, setStatus] = useState(''), [password, setPassword] = useState('')
  const p = snapshot.permissions, s = snapshot.settings, bridge = snapshot.messageBridge
  const execute = (action: string, data: object = {}) => void run(action, data).then(result => setStatus(typeof result === 'string' ? result : JSON.stringify(result, null, 2) ?? '好了')).catch(() => undefined)
  const quiet = (action: string, data: object = {}) => void run(action, data).catch(() => undefined)
  const accessibility = p.accessibility.serviceStatus === 'connected' || p.accessibility.serviceStatus === 'enabled'
  const tiles: [string, boolean, ReactNode, () => void][] = [
    ['QQ', bridge?.connected === true, <ChatsCircle key="q" size={22} weight="duotone"/>, () => document.getElementById('xk-qq')?.scrollIntoView({behavior: 'smooth', block: 'start'})],
    ['悬浮窗', p.overlay, <PictureInPicture key="o" size={22} weight="duotone"/>, () => quiet('openPermission', {kind: 'overlay'})],
    ['通知', p.postNotifications === true, <Bell key="n" size={22} weight="duotone"/>, () => quiet('notificationPermission')],
    ['无障碍', accessibility, <HandPointing key="a" size={22} weight="duotone"/>, () => quiet('openPermission', {kind: 'accessibility'})],
    ['电池', p.batteryExempt === true, <BatteryCharging key="b" size={22} weight="duotone"/>, () => quiet('requestBatteryExemption')],
    ['常驻', s.backgroundEnabled, <DeviceMobile key="r" size={22} weight="duotone"/>, () => quiet('settings', {...s, backgroundEnabled: !s.backgroundEnabled})],
  ]
  const missing = tiles.filter(t => !t[1]).length
  return <div className="xk-scroll"><div className="xk-page">
    <PageHeader title="连接"><span className="xk-pill">{missing ? `${missing} 项没开` : '都好了'}</span></PageHeader>
    <div className="xk-tiles">{tiles.map(([name, ok, icon, act]) => <button key={name} className={'xk-tile' + (ok ? ' ok' : '')} disabled={!nativeCompanion} onClick={act}>
      {icon}<span>{name}</span>{ok ? <CheckCircle className="xk-tile-state" size={16} weight="fill"/> : <WarningCircle className="xk-tile-state" size={16} weight="fill"/>}
    </button>)}</div>

    {status && <div className="xk-card xk-result"><div className="xk-card-head"><h3>刚才的结果</h3><button className="xk-icon-btn" aria-label="清除操作结果" onClick={() => setStatus('')}><X size={18} weight="bold"/></button></div><pre>{status}</pre></div>}

    <div className="xk-stack">
      <Section icon={<GameController size={20} weight="duotone"/>} title="一起玩游戏" summary="她看画面、陪你玩，也能帮你操作">
        <GameSessionPanel run={run} available={nativeCompanion && snapshot.modules?.device.available === true}/>
      </Section>
      <div id="xk-qq"><Section icon={<ChatsCircle size={20} weight="duotone"/>} title="QQ" summary={bridge?.connected ? `已连接${bridge.account ? ' · ' + bridge.account : ''}` : bridge?.status || '还没连'}>
        <QqConnection snapshot={snapshot} run={run} bare/>
        {snapshot.conversations.length > 0 && <div className="xk-stack">
          <h3 className="xk-list-title">会话</h3>
          {snapshot.conversations.map(c => <Conversation key={c.id} value={c} run={run} claudeReady={Boolean(snapshot.claude?.installed && snapshot.claude.settings.enabled)}/>)}
        </div>}
        <div className="xk-actions"><button className="xk-btn" onClick={() => quiet('silenceGroups')}>所有群先静音</button><button className="xk-btn ghost" onClick={() => quiet('openApp', {package: 'com.tencent.mobileqq'})}>打开 QQ</button></div>
        <details className="xk-more"><summary>备用：通过 QQ 通知收消息</summary>
          <label className="xk-switch-row"><span>读取 QQ 通知</span><input type="checkbox" checked={s.qqEnabled ?? false} onChange={e => quiet('settings', {...s, qqEnabled: e.target.checked})}/></label>
          <QqNotificationLabel snapshot={snapshot} run={run}/>
        </details>
      </Section></div>
      <Section icon={<PictureInPicture size={20} weight="duotone"/>} title="悬浮陪伴" summary={snapshot.overlaySession?.running ? `进行中 · 还剩 ${Math.ceil(snapshot.overlaySession.remainingSeconds / 60)} 分钟` : '在别的应用里也能找她'}>
        <OverlaySession snapshot={snapshot} run={run}/>
      </Section>
      <Section icon={<BatteryCharging size={20} weight="duotone"/>} title="让她一直在" summary="防止被系统清理后台">
        <KeepAlive snapshot={snapshot} run={run}/>
      </Section>
      <Section icon={<Wrench size={20} weight="duotone"/>} title="权限" summary={`Android ${p.androidVersion}`}>
        <div className="xk-list">{([
          ['accessibility', '无障碍', accessibility, '看界面、点按钮'],
          ['notifications', 'QQ 通知读取', p.notificationAccess, '备用的收消息方式'],
          ['overlay', '悬浮窗', p.overlay, '悬浮球和小窗'],
          ['battery', '电池限制', p.batteryExempt === true, '后台不被冻结'],
        ] as const).map(([kind, name, ok, detail]) => <div className="xk-row" key={kind}>
          <span><strong>{name}</strong><small>{detail}</small></span>
          <span className={'xk-state' + (ok ? ' ok' : '')}>{ok ? '已开' : '没开'}</span>
          <button className="xk-btn small" onClick={() => quiet('openPermission', {kind})}>去设置</button>
        </div>)}</div>
        <label>设备控制密码<input type="password" autoComplete="current-password" value={password} placeholder="设过才需要填" onChange={e => setPassword(e.target.value)}/></label>
        <button className="xk-btn" onClick={() => void run('grantApps', {password}).then(() => { setPassword(''); setStatus('QQ 和 B站已经允许她操作') }).catch(() => undefined)}>允许她操作 QQ 和 B站</button>
      </Section>
      <Section icon={<Television size={20} weight="duotone"/>} title="B站视频" summary="截一段字幕和画面给她看">
        <VideoPanel run={run} networkEnabled={s.networkEnabled}/>
      </Section>
      <Section icon={<FileText size={20} weight="duotone"/>} title="看过的资料" summary={snapshot.observations.length ? `${snapshot.observations.length} 份` : '还没有'}>
        {snapshot.observations.length === 0 ? <p className="xk-hint">B站片段、文件、截图会存在这里，聊天时可以附上。</p> : <div className="xk-stack">{snapshot.observations.slice().reverse().map(o => <Observation key={o.id} value={o} execute={execute}/>)}</div>}
      </Section>
      <Section icon={<Eye size={20} weight="duotone"/>} title="设备细节控制" summary="观察、收音、手动点按">
        <DevicePanel state={snapshot.modules?.device ?? {}} available={nativeCompanion && snapshot.modules?.device.available === true} run={(action, data) => run('module', {domain: 'device', action, data})}/>
      </Section>
    </div>
  </div></div>
}

function Observation({value: o, execute}: {value: Entry; execute: (action: string, data?: object) => void}) {
  return <article className="xk-card inset">
    <div className="xk-card-head"><h3>{field(o, 'videoId') || field(o, 'source')}</h3><span className="xk-pill">{o.temporary ? '临时' : o.analyzed ? '看过了' : '还没看'}</span></div>
    <p className="xk-hint">{field(o, 'coverage')}</p>
    {Boolean(o.analyzed) && <details className="xk-more"><summary>她的理解</summary><p>{field(o, 'analysis')}</p></details>}
    <div className="xk-actions">
      {Boolean(o.temporary) && <button className="xk-btn small" onClick={() => execute('saveObservation', {id: o.id})}>保存</button>}
      {Boolean(o.analyzed) && <button className="xk-btn small" onClick={() => { if (confirm('把这份理解记进她的记忆？')) execute('rememberObservation', {id: o.id}) }}>记住</button>}
      <button className="xk-btn small ghost danger" onClick={() => { if (confirm('删除这份资料和原始文件？')) execute('deleteObservation', {id: o.id}) }}>删除</button>
    </div>
  </article>
}

function Conversation({value, run, claudeReady}: {value: Entry; run: Run; claudeReady: boolean}) {
  const [draft, setDraft] = useState(''), [capture, setCapture] = useState(Boolean(value.captureEnabled)), [night, setNight] = useState(value.nightQuiet !== false), [images, setImages] = useState(Boolean(value.allowImages))
  const [engine, setEngine] = useState(field(value, 'engine') === 'claude' ? 'claude' : 'gemini')
  const group = value.id.startsWith('qq:group:')
  const [mode, setMode] = useState(field(value, 'mode') || 'quiet'), [cooldown, setCooldown] = useState(Number(value.cooldownSeconds) || 60), [hourly, setHourly] = useState(Number(value.hourlyLimit) || 10), [auto, setAuto] = useState(Boolean(value.autoReply)), [saved, setSaved] = useState(false)
  return <Section title={field(value, 'title')} summary={value.reliable ? ({quiet: '只看不说', mention: '@她才回', natural: '自然参与'} as Record<string, string>)[mode] : '还没识别，不能自动回'}>
    <form onSubmit={e => { e.preventDefault(); void run('conversation', {id: value.id, mode, cooldownSeconds: cooldown, hourlyLimit: hourly, autoReply: auto, threshold: 0.65, captureEnabled: capture, nightQuiet: night, allowImages: images, retentionDays: 30}).then(() => setSaved(true)).catch(() => undefined) }}>
      <label className="xk-switch-row"><span>接收这个会话</span><input type="checkbox" checked={capture} onChange={e => setCapture(e.target.checked)}/></label>
      <label className="xk-switch-row"><span>夜里只回你的 @<small>0 点到 8 点</small></span><input type="checkbox" checked={night} onChange={e => setNight(e.target.checked)}/></label>
      <label className="xk-switch-row"><span>看图片</span><input type="checkbox" checked={images} onChange={e => setImages(e.target.checked)}/></label>
      {group && <label className="xk-switch-row"><span>用 Claude 回复<small>{engine === 'claude' ? (claudeReady ? '下一条消息起由 Claude 回；Claude 不可用时换回 Gemini' : '已选 Claude，但 我的 → Claude 还没装好或没打开，现在仍用 Gemini') : '默认 Gemini；关着时这个群的消息不经过 Claude'}</small></span>
        <input type="checkbox" checked={engine === 'claude'} onChange={e => { const next = e.target.checked ? 'claude' : 'gemini'; void run('conversationEngine', {id: value.id, engine: next}).then(() => setEngine(next)).catch(() => undefined) }}/></label>}
      <label>参与方式<select value={mode} disabled={!value.reliable} onChange={e => { setMode(e.target.value); setSaved(false) }}><option value="quiet">只看不说</option><option value="mention">@她才回</option><option value="natural">自然参与</option></select></label>
      <div className="xk-grid"><label>最短间隔（秒）<input type="number" min={10} max={86400} value={cooldown} onChange={e => setCooldown(Number(e.target.value))}/></label><label>每小时最多<input type="number" min={1} max={60} value={hourly} onChange={e => setHourly(Number(e.target.value))}/></label></div>
      <label className="xk-switch-row"><span>直接发出回复<small>关掉时先进待审</small></span><input type="checkbox" checked={auto} disabled={!value.reliable || mode === 'quiet'} onChange={e => setAuto(e.target.checked)}/></label>
      <div className="xk-actions"><button className="xk-btn primary" type="submit">{saved ? '已保存' : '保存'}</button><button className="xk-btn ghost danger" type="button" onClick={() => { if (confirm('清空这个会话的消息、记忆和任务，并停止接收？')) void run('clearConversation', {scope: value.scope}).catch(() => undefined) }}>清空</button></div>
      <label>替她写一条回复<textarea rows={2} value={draft} onChange={e => setDraft(e.target.value)} maxLength={4000}/></label>
      <button className="xk-btn" type="button" disabled={!draft.trim()} onClick={() => void run('draftReply', {scope: value.scope, body: draft}).then(() => setDraft('')).catch(() => undefined)}>放进待审</button>
    </form>
  </Section>
}
