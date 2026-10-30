import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, ChatCircleDots, CircleNotch, GearSix, Leaf, PlugsConnected, Sparkle } from '@phosphor-icons/react'
import 'lxgw-wenkai-webfont/lxgwwenkai-regular.css'
import 'lxgw-wenkai-webfont/lxgwwenkai-bold.css'
import './companion.css'
import { command, companion, field, nativeCompanion, ownerScope, type Run, type Snapshot } from './bridge'
import { Onboarding } from './Onboarding'
import { subscribePresence, type PresenceState } from './presence'
import { LifePanel } from './life/LifePanel'
import { ChatPage } from './ChatPage'
import { SettingsPage } from './SettingsPage'
import { ActivityPage } from './ActivityPage'
import { ConnectPage } from './ConnectPage'
import { PageHeader, Toast } from './ui'
const AgentWorkbench = lazy(() => import('../App').then(module => ({default: module.App})))

export type Tab = 'chat' | 'memory' | 'tasks' | 'device' | 'settings'
const tabs: [Tab, string, typeof ChatCircleDots][] = [['chat', '聊天', ChatCircleDots], ['memory', '生活', Leaf], ['tasks', '动态', Sparkle], ['device', '连接', PlugsConnected], ['settings', '我的', GearSix]]

export function CompanionApp() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [presence, setPresence] = useState<PresenceState | null>(null)
  const [tab, setTab] = useState<Tab>('chat')
  const [workbench, setWorkbench] = useState(false)
  const [setupOpen, setSetupOpen] = useState<boolean | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const jobs = useRef(0)
  const loadSequence = useRef(0)
  const reload = useCallback(async () => {
    const sequence = ++loadSequence.current
    const value = await companion.state()
    if (sequence === loadSequence.current) setSnapshot(value)
  }, [])
  useEffect(() => {
    void reload().catch(e => setError(String(e)))
    const timer = window.setInterval(() => { void reload().catch(() => undefined) }, 2500)
    return () => window.clearInterval(timer)
  }, [reload])
  useEffect(() => {
    if (!nativeCompanion) return
    return subscribePresence(companion, setPresence, error => setError(error instanceof Error ? error.message : String(error)))
  }, [])
  useEffect(() => {
    if (!nativeCompanion) return
    let disposed = false, timer: number | undefined
    let subscription: {remove(): Promise<void>} | undefined
    void companion.addListener('moduleState', () => {
      if (disposed || timer !== undefined) return
      timer = window.setTimeout(() => { timer = undefined; void reload().catch(() => undefined) }, 100)
    }).then(value => { if (disposed) void value.remove(); else subscription = value }).catch(e => setError(String(e)))
    return () => { disposed = true; window.clearTimeout(timer); void subscription?.remove() }
  }, [reload])
  const run: Run = async (action, data = {}) => {
    jobs.current++; setBusy(true); setError('')
    try { const value = await command(action, data); await reload(); return value }
    catch (e) { const message = e instanceof Error ? e.message : String(e); setError(message); throw e }
    finally { if (--jobs.current === 0) setBusy(false) }
  }
  const clearError = useCallback(() => setError(''), [])

  if (workbench) return <><button className="companion-return" onClick={() => setWorkbench(false)}><ArrowLeft size={16} weight="bold"/> 回到小克</button><Suspense fallback={<div className="xk-splash"><CircleNotch className="xk-spin" size={28}/><p>正在打开工作台…</p></div>}><AgentWorkbench /></Suspense></>
  if (!snapshot) return <div className="xk-app xk-splash-app"><div className="xk-splash">
    <span className="xk-splash-mark" aria-hidden="true">克</span>
    {error ? <><p className="xk-splash-error">{error}</p><button className="xk-btn primary" onClick={() => { setError(''); void reload().catch(e => setError(String(e))) }}>重试</button></> : <p>小克正在醒来…</p>}
  </div></div>

  const setupVisible = setupOpen ?? !snapshot.settings.onboardingCompleted
  const pending = snapshot.tasks.filter(t => ['draft', 'unknown', 'failed', 'waiting'].includes(field(t, 'state'))).length
    + ((snapshot.modules?.social?.approvals as {state?: string}[] | undefined)?.filter(a => a.state === 'pending').length ?? 0)
  const name = snapshot.persona.name || '小克'
  const lifeScopes = [{id: ownerScope, label: '我和她的私聊'}, ...snapshot.conversations.map(c => ({id: c.scope, label: field(c, 'title') || c.scope}))]

  return <div className="xk-app">
    {busy && <div className="xk-progress" role="status" aria-label="正在处理"/>}
    <Toast message={error} onClose={clearError}/>
    <nav className="xk-tabbar" aria-label="主导航">
      <div className="xk-rail-brand"><span className="xk-rail-mark" aria-hidden="true">克</span><strong>{name}</strong></div>
      {tabs.map(([key, label, Icon]) => <button key={key} className={'xk-tab' + (tab === key ? ' selected' : '')} aria-current={tab === key ? 'page' : undefined} onClick={() => setTab(key)}>
        <span className="xk-tab-icon"><Icon size={24} weight={tab === key ? 'fill' : 'duotone'}/>{key === 'tasks' && pending > 0 && <b className="xk-badge">{pending > 99 ? '99+' : pending}</b>}</span>
        <span className="xk-tab-label">{label}</span>
      </button>)}
    </nav>
    <main className="xk-main" key={tab}>
      {tab === 'chat' && <ChatPage snapshot={snapshot} presence={presence} run={run} onSetup={() => setTab('settings')}/>}
      {tab === 'memory' && <div className="xk-scroll"><div className="xk-page"><PageHeader title="生活"/>
        <LifePanel state={snapshot.modules?.life ?? {}} available={nativeCompanion && snapshot.modules?.life.available === true} scopes={lifeScopes} run={(action, data) => run('module', {domain: 'life', action, data: data ?? {}})} saveFile={(file, text) => run('saveDocument', {name: file, text})}/>
      </div></div>}
      {tab === 'tasks' && <ActivityPage snapshot={snapshot} run={run}/>}
      {tab === 'device' && <ConnectPage snapshot={snapshot} run={run}/>}
      {tab === 'settings' && <SettingsPage snapshot={snapshot} run={run} onSetup={() => setSetupOpen(true)} onWorkbench={() => setWorkbench(true)}/>}
    </main>
    {setupVisible && <Onboarding snapshot={snapshot} run={run} onClose={() => setSetupOpen(false)}/>}
  </div>
}
