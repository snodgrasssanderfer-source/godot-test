import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { CaretRight, ChatCircleDots, FileText, Key, Microphone, Pause, PaperPlaneRight, Play, Plus, SpeakerHigh, SpeakerSlash, Stop, X } from '@phosphor-icons/react'
import { field, nativeCompanion, ownerScope, type Entry, type Run, type Snapshot } from './bridge'
import { PresencePanel, type PresenceState } from './presence'
import { DocumentsPanel } from './iteration/DocumentsPanel'
import { Avatar, Empty, Sheet } from './ui'

const time = (value: unknown) => typeof value === 'number' ? new Date(value).toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit', hour12: false}) : ''
function dayLabel(value: number) {
  const d = new Date(value), today = new Date()
  const diff = Math.round((new Date(today.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000)
  return diff === 0 ? '今天' : diff === 1 ? '昨天' : d.toLocaleDateString('zh-CN', {month: 'long', day: 'numeric', weekday: 'short'})
}

export function ChatPage({snapshot, presence, run, onSetup}: {snapshot: Snapshot; presence: PresenceState | null; run: Run; onSetup: () => void}) {
  const [draft, setDraft] = useState(''), [scope, setScope] = useState(ownerScope), [observation, setObservation] = useState('')
  const [attachOpen, setAttachOpen] = useState(false), [presenceOpen, setPresenceOpen] = useState(false)
  const name = snapshot.persona.name || '小克'
  const messages = snapshot.messages.filter(m => m.scope === scope).slice(-80)
  const active = snapshot.tasks.find(t => t.scope === scope && field(t, 'type') === 'chat' && ['queued', 'running'].includes(field(t, 'state')))
  const partial = active ? field(active, 'partialText') : ''
  const live = presence ?? snapshot.presence
  const settings = snapshot.settings
  const list = useRef<HTMLDivElement>(null), input = useRef<HTMLTextAreaElement>(null)
  // Only messages that arrive while the page is open get the entrance animation.
  const seen = useRef<Set<string> | null>(null), seenScope = useRef(scope)
  if (seenScope.current !== scope) { seenScope.current = scope; seen.current = null }
  if (seen.current === null && messages.length > 0) seen.current = new Set(messages.map(m => m.id))
  const stick = useRef(true), settled = useRef(false)
  useEffect(() => { settled.current = false; stick.current = true }, [scope])
  useEffect(() => {
    const el = list.current
    if (el && stick.current) el.scrollTo({top: el.scrollHeight, behavior: settled.current ? 'smooth' : 'auto'})
    settled.current = true
  }, [messages.length, partial, active?.id, scope])
  useLayoutEffect(() => {
    const el = input.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 148) + 'px'
  }, [draft])
  async function send(e: FormEvent) {
    e.preventDefault()
    if (!draft.trim() || active) return
    stick.current = true
    try { await run('chat', {text: draft, observationId: observation}); setDraft(''); setObservation('') } catch { /* toast shows it */ }
  }
  const status = settings.paused ? '暂停中' : active ? (partial ? '正在输入…' : '在想…') : live?.running ? '陪你中' : settings.networkEnabled ? '在线' : '还没联网'
  const attached = snapshot.observations.find(o => o.id === observation)
  const ownScope = scope === ownerScope

  return <div className="xk-chat">
    <header className="xk-chat-head">
      <Avatar name={name} size={42} live={!settings.paused && settings.networkEnabled}/>
      <div className="xk-chat-title">
        <strong>{name}</strong>
        {snapshot.conversations.length > 0
          ? <label className="xk-scope"><span className="sr-only">会话</span><select value={scope} onChange={e => setScope(e.target.value)}><option value={ownerScope}>{status} · 私聊</option>{snapshot.conversations.map(c => <option key={c.id} value={c.scope}>{field(c, 'title') || c.scope}</option>)}</select></label>
          : <small className={active ? 'xk-typing-text' : ''}>{status}</small>}
      </div>
      <button className={'xk-icon-btn' + (live?.running ? ' on' : '')} aria-label="语音与悬浮陪伴" onClick={() => setPresenceOpen(true)}><Microphone size={22} weight={live?.running ? 'fill' : 'duotone'}/></button>
      <button className="xk-icon-btn" aria-label={settings.paused ? '让她继续' : '暂停她的活动'} onClick={() => void run('pause', {paused: !settings.paused}).catch(() => undefined)}>{settings.paused ? <Play size={22} weight="fill"/> : <Pause size={22} weight="duotone"/>}</button>
    </header>

    {live?.running && <div className="xk-live-bar">
      <span className="xk-live-dot"/><span>{live.text ? live.text.slice(0, 60) : '语音陪伴中'}</span>
      <button aria-label="打断当前回应" onClick={() => void run('presenceInterrupt').catch(() => undefined)}><Stop size={18} weight="fill"/></button>
      <button aria-label={live.muted ? '恢复语音' : '静音语音'} onClick={() => void run('presenceMute', {muted: !live.muted}).catch(() => undefined)}>{live.muted ? <SpeakerSlash size={18} weight="fill"/> : <SpeakerHigh size={18} weight="fill"/>}</button>
      <button aria-label="结束陪伴会话" onClick={() => void run('presenceStop').catch(() => undefined)}><X size={18} weight="bold"/></button>
    </div>}

    <div className="xk-chat-list" ref={list} onScroll={e => { const el = e.currentTarget; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80 }}>
      {!snapshot.keyConfigured && ownScope && <button className="xk-banner" onClick={onSetup}><Key size={24} weight="duotone"/><span><strong>先连上模型</strong><small>填好地址和密钥，她就能回你了</small></span><CaretRight size={18} weight="bold"/></button>}
      {messages.length === 0 && !active
        ? <Empty icon={<ChatCircleDots size={34} weight="duotone"/>} title={ownScope ? `和${name}打个招呼吧` : '这里还没有消息'}/>
        : <Messages messages={messages} seen={seen.current}/>}
      {active && <div className="xk-msg-row assistant xk-new">
        <div className="xk-bubble assistant">{partial ? <>{partial}<span className="xk-caret"/></> : <span className="xk-dots" aria-label="正在输入"><i/><i/><i/></span>}</div>
        <button className="xk-chip-btn" onClick={() => void run('cancel', {id: active.id}).catch(() => undefined)}>停下</button>
      </div>}
    </div>

    {ownScope ? <form className="xk-composer" onSubmit={e => void send(e)}>
      {attached && <div className="xk-attached"><FileText size={16} weight="duotone"/><span>{field(attached, 'videoId') || field(attached, 'source')}</span><button type="button" aria-label="取消附加" onClick={() => setObservation('')}><X size={14} weight="bold"/></button></div>}
      <div className="xk-composer-row">
        <button type="button" className="xk-icon-btn" aria-label="附加资料" onClick={() => setAttachOpen(true)}><Plus size={22} weight="bold"/></button>
        <label className="sr-only" htmlFor="companion-message">发给小克的消息</label>
        <textarea id="companion-message" ref={input} rows={1} placeholder="说点什么…" value={draft} maxLength={12000} onChange={e => setDraft(e.target.value)}/>
        <button className="xk-send" type="submit" aria-label="发送" disabled={!draft.trim() || Boolean(active)}><PaperPlaneRight size={20} weight="fill"/></button>
      </div>
    </form> : <p className="xk-readonly">群聊和其他会话在这里只能看，回复草稿去「动态」里处理。</p>}

    <Sheet open={attachOpen} title="一起看点什么" onClose={() => setAttachOpen(false)}>
      <DocumentsPanel run={run} onAttach={id => { setObservation(id); setAttachOpen(false) }}/>
      {snapshot.observations.length > 0 && <div className="xk-list">
        <h3 className="xk-list-title">已有资料</h3>
        {snapshot.observations.slice().reverse().map(o => <button key={o.id} className={'xk-row-btn' + (o.id === observation ? ' selected' : '')} onClick={() => { setObservation(o.id === observation ? '' : o.id); setAttachOpen(false) }}>
          <FileText size={20} weight="duotone"/><span><strong>{field(o, 'videoId') || field(o, 'source')}</strong><small>{o.analyzed ? '看过了' : '还没看'}</small></span>
        </button>)}
      </div>}
    </Sheet>
    <Sheet open={presenceOpen} title="语音与悬浮陪伴" onClose={() => setPresenceOpen(false)}>
      <PresencePanel state={{...(live ?? {phase: 'idle', displayName: name, mode: 'companion', running: false, available: false}), character: snapshot.character, characterStorage: nativeCompanion}} onCommand={run}/>
    </Sheet>
  </div>
}

function Messages({messages, seen}: {messages: Entry[]; seen: Set<string> | null}) {
  let lastDay = ''
  return <>{messages.map(m => {
    const role = field(m, 'role') === 'user' ? 'user' : 'assistant'
    const at = typeof m.createdAt === 'number' ? m.createdAt : 0
    const day = at ? dayLabel(at) : ''
    const divider = day && day !== lastDay ? <div className="xk-day" key={'d' + m.id}><span>{day}</span></div> : null
    if (day) lastDay = day
    const source = field(m, 'source'), fresh = seen && !seen.has(m.id) ? ' xk-new' : ''
    if (field(m, 'role') === 'notice') return [divider, <div key={m.id} className={'xk-notice' + fresh}>{field(m, 'text')}</div>]
    const note = source && source !== '本机主人' && !source.startsWith('模型') ? ' · ' + source : ''
    return [divider, <div key={m.id} className={'xk-msg-row ' + role + fresh}>
      <div className={'xk-bubble ' + role}>{field(m, 'text')}</div>
      <small className="xk-meta">{time(at)}{note}</small>
    </div>]
  })}</>
}
