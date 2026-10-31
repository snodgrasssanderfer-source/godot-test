import { ClaudePermissions } from './ClaudePanel'
import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { CaretRight, ChatCircleDots, Check, Copy, FileText, Image as ImageIcon, Key, Microphone, Pause, PaperPlaneRight, Play, Plus, SpeakerHigh, SpeakerSlash, Stop, X } from '@phosphor-icons/react'
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

/** Drafts survive leaving the page, one per conversation. Storage can be unavailable; then they simply don't. */
const draftKey = (scope: string) => 'xk-draft:' + scope
function loadDraft(scope: string) { try { return localStorage.getItem(draftKey(scope)) ?? '' } catch { return '' } }
function saveDraft(scope: string, text: string) { try { if (text) localStorage.setItem(draftKey(scope), text); else localStorage.removeItem(draftKey(scope)) } catch { /* not kept */ } }

async function copyText(text: string) {
  try { await navigator.clipboard.writeText(text); return true } catch {
    // Some WebViews refuse the async clipboard; the old way still works there.
    const area = document.createElement('textarea'); area.value = text; area.style.position = 'fixed'; area.style.opacity = '0'
    document.body.appendChild(area); area.select()
    try { return document.execCommand('copy') } finally { area.remove() }
  }
}

/** A picture from the phone, shrunk to at most 2048 px and re-encoded as JPEG so it fits the media path. */
async function pictureToJpeg(file: File): Promise<string> {
  const url = URL.createObjectURL(file)
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => { const i = new Image(); i.onload = () => resolve(i); i.onerror = () => reject(new Error('图片打不开')); i.src = url })
    const scale = Math.min(1, 2048 / Math.max(img.naturalWidth, img.naturalHeight))
    const canvas = document.createElement('canvas'); canvas.width = Math.round(img.naturalWidth * scale); canvas.height = Math.round(img.naturalHeight * scale)
    canvas.getContext('2d')?.drawImage(img, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg', 0.86).split(',')[1] ?? ''
  } finally { URL.revokeObjectURL(url) }
}

export function ChatPage({snapshot, presence, run, onSetup}: {snapshot: Snapshot; presence: PresenceState | null; run: Run; onSetup: () => void}) {
  const [scope, setScope] = useState(ownerScope), [draft, setDraftState] = useState(() => loadDraft(ownerScope)), [observation, setObservation] = useState('')
  const [picking, setPicking] = useState(false)
  const picture = useRef<HTMLInputElement>(null)
  const setDraft = (text: string) => { setDraftState(text); saveDraft(scope, text) }
  useEffect(() => { setDraftState(loadDraft(scope)) }, [scope])
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
    if (!draft.trim()) return
    stick.current = true
    // While she is still answering, this joins her current turn (Gemini) or comes right after it (Claude).
    try { await run('chat', {text: draft, observationId: observation}); setDraft(''); setObservation('') } catch { /* toast shows it */ }
  }
  async function attachPicture(file: File) {
    setPicking(true)
    try {
      const base64 = await pictureToJpeg(file)
      const result = await run('importMedia', {mime: 'image/jpeg', base64, name: file.name || '图片'}) as {id?: string}
      if (result?.id) setObservation(result.id)
    } catch { /* toast shows it */ } finally { setPicking(false) }
  }
  const steps = active && Array.isArray(active.steps) ? (active.steps as {text: string}[]) : []
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
      {ownScope && <ClaudePermissions items={snapshot.claude?.permissions ?? []} run={run}/>}
      {!snapshot.keyConfigured && ownScope && <button className="xk-banner" onClick={onSetup}><Key size={24} weight="duotone"/><span><strong>先连上模型</strong><small>填好地址和密钥，她就能回你了</small></span><CaretRight size={18} weight="bold"/></button>}
      {messages.length === 0 && !active
        ? <Empty icon={<ChatCircleDots size={34} weight="duotone"/>} title={ownScope ? `和${name}打个招呼吧` : '这里还没有消息'}/>
        : <Messages messages={messages} seen={seen.current}/>}
      {active && <div className="xk-msg-row assistant xk-new">
        {steps.length > 0 && <details className="xk-steps-live" open={!partial}>
          <summary>{steps[steps.length - 1].text}{steps.length > 1 ? ` · 共 ${steps.length} 步` : ''}</summary>
          <ol>{steps.slice(-8).map((s, i) => <li key={i}>{s.text}</li>)}</ol>
        </details>}
        <div className="xk-bubble assistant">{partial ? <>{partial}<span className="xk-caret"/></> : <span className="xk-dots" aria-label="正在输入"><i/><i/><i/></span>}</div>
        {!partial && /还在想|重试|降级|换个方式/.test(field(active, 'progress')) && <small className="xk-meta">{field(active, 'progress')}</small>}
        <button className="xk-chip-btn" onClick={() => void run('cancel', {id: active.id}).catch(() => undefined)}>停下</button>
      </div>}
    </div>

    {ownScope ? <form className="xk-composer" onSubmit={e => void send(e)}>
      {picking && <div className="xk-attached"><ImageIcon size={16} weight="duotone"/><span>正在准备图片…</span></div>}
      {attached && <div className="xk-attached"><FileText size={16} weight="duotone"/><span>{field(attached, 'videoId') || field(attached, 'source')}</span><button type="button" aria-label="取消附加" onClick={() => setObservation('')}><X size={14} weight="bold"/></button></div>}
      <div className="xk-composer-row">
        <button type="button" className="xk-icon-btn" aria-label="附加资料" onClick={() => setAttachOpen(true)}><Plus size={22} weight="bold"/></button>
        <button type="button" className="xk-icon-btn" aria-label="发图片" disabled={picking || !nativeCompanion} onClick={() => picture.current?.click()}><ImageIcon size={22} weight="duotone"/></button>
        <input ref={picture} hidden type="file" accept="image/*" onChange={e => { const f = e.target.files?.[0]; if (f) void attachPicture(f); e.target.value = '' }}/>
        <label className="sr-only" htmlFor="companion-message">发给小克的消息</label>
        <textarea id="companion-message" ref={input} rows={1} placeholder={active ? '她还在说，也可以接着讲…' : '说点什么…'} value={draft} maxLength={12000} onChange={e => setDraft(e.target.value)}/>
        <button className="xk-send" type="submit" aria-label="发送" disabled={!draft.trim()}><PaperPlaneRight size={20} weight="fill"/></button>
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

/** Which engine wrote a reply, as a short tag: "Claude · opus", "Gemini · flash（Claude 顶不上时）". */
function engineTag(m: Entry) {
  const source = field(m, 'source')
  if (source.startsWith('Claude ')) return 'Claude · ' + source.slice(7).replace(/^claude-/, '')
  if (source.startsWith('模型 ')) return 'Gemini · ' + source.slice(3).replace(/^gemini-/, '') + (field(m, 'fallback') ? '（Claude 不可用时顶上）' : '')
  return ''
}

function CopyButton({text, label = '复制'}: {text: string; label?: string}) {
  const [done, setDone] = useState(false)
  return <button type="button" className="xk-copy" aria-label={label} onClick={() => void copyText(text).then(ok => { if (ok) { setDone(true); window.setTimeout(() => setDone(false), 1500) } })}>
    {done ? <Check size={14} weight="bold"/> : <Copy size={14} weight="duotone"/>}{done ? '已复制' : label}
  </button>
}

/** Plain text with ``` fenced code shown as blocks that copy on their own. */
function RichText({text}: {text: string}) {
  const parts = text.split(/```([^\n`]*)\n([\s\S]*?)```/g)
  if (parts.length === 1) return <>{text}</>
  const out: ReactNode[] = []
  for (let i = 0; i < parts.length; i += 3) {
    if (parts[i]) out.push(<span key={'t' + i}>{parts[i]}</span>)
    if (i + 2 < parts.length) out.push(<div key={'c' + i} className="xk-code"><div className="xk-code-head"><span>{parts[i + 1] || '代码'}</span><CopyButton text={parts[i + 2]} label="复制代码"/></div><pre><code>{parts[i + 2]}</code></pre></div>)
  }
  return <>{out}</>
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
    const tag = role === 'assistant' ? engineTag(m) : ''
    const note = tag ? ' · ' + tag : source && source !== '本机主人' && !source.startsWith('模型') && !source.startsWith('Claude ') ? ' · ' + source : ''
    return [divider, <div key={m.id} className={'xk-msg-row ' + role + fresh}>
      <div className={'xk-bubble ' + role}><RichText text={field(m, 'text')}/></div>
      <small className="xk-meta">{time(at)}{note}<CopyButton text={field(m, 'text')}/></small>
    </div>]
  })}</>
}
