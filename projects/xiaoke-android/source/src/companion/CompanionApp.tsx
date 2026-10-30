import { lazy, Suspense, useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft, BookOpen, Check, ChevronRight, Download, KeyRound, Loader2, MessageCircle, Pause, Play, Plus, Settings2, Shield, Smartphone, Sparkles, Upload, X } from 'lucide-react'
import { command, companion, field, nativeCompanion, ownerScope, profile, type Entry, type Persona, type Settings, type Snapshot } from './bridge'
import './companion.css'
import { downloadText, readCharacterCard } from './characterCard'
import { Connections } from './Connections'
import { VideoPanel } from './VideoPanel'
import { KeepAlive } from './KeepAlive'
import { DailyBudget } from './DailyBudget'
import { Onboarding } from './Onboarding'
import { PresencePanel, subscribePresence, type PresenceState } from './presence'
import { DocumentsPanel } from './iteration/DocumentsPanel'
import { GameSessionPanel } from './iteration/GameSessionPanel'
import { DevicePanel } from './devicev2/DevicePanel'
import { SocialPanel } from './social/SocialPanel'
import { LifePanel } from './life/LifePanel'
const AgentWorkbench = lazy(() => import('../App').then(module => ({default: module.App})))

type Tab = 'chat' | 'models' | 'persona' | 'memory' | 'tasks' | 'device'
type Run = (action: string, data?: object) => Promise<unknown>
const labels: Record<Tab, string> = {chat: '陪伴', memory: '生活', tasks: '活动', device: '连接', persona: '角色', models: '设置'}
const states: Record<string, string> = {queued:'排队', running:'处理中', waiting:'等待恢复', draft:'待审草稿', approved:'已授权', sending:'提交中', unknown:'待核对结果', done:'完成', failed:'失败', cancelled:'已取消'}

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
  const quietRun: Run = (a,d) => run(a,d).catch(() => undefined)
  if (workbench) return <><button className="companion-return" onClick={() => setWorkbench(false)}><ArrowLeft size={16}/> 返回助手</button><Suspense fallback={<div className="companion-loading"><Loader2 className="spin"/>正在打开工作台…</div>}><AgentWorkbench /></Suspense></>
  if (!snapshot) return <div className="companion-loading"><Loader2 className="spin"/><p>{error || '正在读取本机助手数据…'}</p><button onClick={() => void reload()}>重试</button></div>
  const setupVisible = setupOpen ?? !snapshot.settings.onboardingCompleted
  const pending = snapshot.tasks.filter(t => ['draft','unknown','failed','waiting'].includes(field(t,'state'))).length
  return <div className="companion-app">
    <aside className="companion-nav">
      <div className="companion-brand"><span className="companion-mark">K</span><div><strong>{snapshot.persona.name || '小克'}</strong><small>陪你聊天，也一起行动</small></div></div>
      <nav aria-label="助手导航">{(Object.keys(labels) as Tab[]).map(name => <button key={name} className={tab===name?'selected':''} onClick={() => {setSetupOpen(false); setTab(name)}}>{name==='chat'?<MessageCircle/>:name==='models'?<Sparkles/>:name==='persona'?<BookOpen/>:name==='memory'?<Shield/>:name==='tasks'?<Check/>:<Smartphone/>}<span>{labels[name]}</span>{name==='tasks' && pending>0 && <b>{pending}</b>}</button>)}</nav>
      <button className="workbench-link" onClick={() => setWorkbench(true)}><Settings2 size={18}/> 原 DSH 工作台 <ChevronRight size={16}/></button>
      <small className="nav-foot">人格与记忆留在本机<br/>由你决定何时连接外界</small>
    </aside>
    <main className="companion-main">
      <header className="companion-top"><div><span className={'status-dot '+(snapshot.settings.networkEnabled?'online':'')}/>{snapshot.settings.paused?'助手已暂停':snapshot.settings.networkEnabled?snapshot.settings.activeModel:'本机模式 · 尚未联网'}</div><button title="暂停模型与外部任务；游戏输入急停在设备控制面板" onClick={() => void quietRun('pause',{paused:!snapshot.settings.paused})}>{snapshot.settings.paused?<Play size={16}/>:<Pause size={16}/>} {snapshot.settings.paused?'继续':'暂停活动'}</button></header>
      <button className="mobile-workbench" onClick={()=>setWorkbench(true)}><Settings2 size={15}/> 原 DSH 工作台 · 文件 / 终端 / 插件</button>
      <button className="setup-guide-link" onClick={() => setSetupOpen(true)}>安装与配置引导</button>
      {setupVisible && <Onboarding snapshot={snapshot} run={run} onClose={() => setSetupOpen(false)} onOpen={setTab}/>}
      {!nativeCompanion && <div className="preview-note">浏览器界面预览 · 数据仅在本浏览器保存；安卓操作与模型调用不可用</div>}
      {error && <div className="companion-error" role="alert"><span>{error}</span><button aria-label="关闭错误提示" onClick={() => setError('')}><X size={18}/></button></div>}
      {busy && <div className="companion-busy" role="status"><Loader2 size={14} className="spin"/> 正在处理；长任务可从顶部暂停</div>}
      {!setupVisible && tab==='chat' && <details className="presence-config"><summary>悬浮陪伴与语音 · {(presence??snapshot.presence)?.running?'会话进行中':'按需开启'}</summary><div className="presence-host"><PresencePanel state={{...(presence ?? snapshot.presence ?? {phase:'idle',displayName:snapshot.persona.name,mode:'companion',running:false,available:false}),character:snapshot.character,characterStorage:nativeCompanion}} onCommand={run}/></div></details>}
      {!setupVisible && tab==='chat' && (presence??snapshot.presence)?.running && <div className="form-actions"><button onClick={()=>void quietRun('presenceInterrupt')}>打断当前回应</button><button onClick={()=>void quietRun('presenceMute',{muted:!(presence??snapshot.presence)?.muted})}>{(presence??snapshot.presence)?.muted?'恢复语音':'静音语音'}</button><button onClick={()=>void quietRun('presenceStop')}>结束陪伴会话</button></div>}
      {!setupVisible && tab==='chat' && <Chat snapshot={snapshot} run={run} onSetup={() => setTab('models')}/>}
      {!setupVisible && tab==='models' && <Models snapshot={snapshot} run={run}/>}
      {!setupVisible && tab==='persona' && <PersonaEditor value={snapshot.persona} versions={snapshot.personaVersions} run={run}/>}
      {!setupVisible && tab==='memory' && <LifePanel state={snapshot.modules?.life??{}} available={nativeCompanion&&snapshot.modules?.life.available===true} scopes={[{id:ownerScope,label:'主人 · 本机私聊'},...snapshot.conversations.map(c=>({id:c.scope,label:field(c,'title')||c.scope}))]} run={(action,data)=>run('module',{domain:'life',action,data:data??{}})} saveFile={(name,text)=>run('saveDocument',{name,text})}/>}
      {!setupVisible && tab==='tasks' && <><section className="content-panel"><div className="card activity-switch"><label className="check-row"><input type="checkbox" checked={snapshot.settings.activityEnabled===true} onChange={e=>void quietRun('settings',{...snapshot.settings,activityEnabled:e.target.checked})}/>启用按已设范围执行外部活动</label><small>需先配置账号与预算。评论、加好友和加群仍须具体审批，开关不会自动登录或替你授权任何目标。</small></div><SocialPanel state={snapshot.modules?.social??{}} available={nativeCompanion&&snapshot.modules?.social.available===true} run={(action,data)=>run('module',{domain:'social',action,data})}/></section><details className="legacy-task-details"><summary>聊天处理与原有发送草稿</summary><Tasks snapshot={snapshot} run={run}/></details></>}
      {!setupVisible && tab==='device' && <><section className="content-panel"><Heading title="手机陪伴与操作" text="观察、收音和输入控制分别开关，关闭浮球不会结束全部联系。"/><GameSessionPanel run={run} available={nativeCompanion&&snapshot.modules?.device.available===true}/><details><summary>设备观察、音频与手势详细控制</summary><DevicePanel state={snapshot.modules?.device??{}} available={nativeCompanion&&snapshot.modules?.device.available===true} run={(action,data)=>run('module',{domain:'device',action,data})}/></details></section><Device snapshot={snapshot} run={run}/></>}
    </main>
  </div>
}

function Heading({title, text}: {title:string; text:string}) { return <div className="companion-heading"><h1>{title}</h1><p>{text}</p></div> }

function Chat({snapshot,run,onSetup}:{snapshot:Snapshot;run:Run;onSetup:()=>void}) {
  const [draft,setDraft]=useState(''), [scope,setScope]=useState(ownerScope), [observation,setObservation]=useState('')
  const messages=snapshot.messages.filter(m=>m.scope===scope).slice(-80)
  const active=snapshot.tasks.find(t=>t.scope===scope && field(t,'type')==='chat' && ['queued','running'].includes(field(t,'state')))
  const end=useRef<HTMLDivElement>(null)
  useEffect(()=>{end.current?.scrollIntoView({behavior:'smooth',block:'end'})},[messages.length])
  async function send(e:FormEvent){e.preventDefault();if(!draft.trim())return;try{await run('chat',{text:draft,observationId:observation});setDraft('');setObservation('')}catch{/* shown above */}}
  return <section className="chat-panel">
    <Heading title="我在这里。" text="聊刚才发生的事，也聊接下来想做什么。其他会话的经历与这里分别保存。"/>
    {!snapshot.keyConfigured && <button className="setup-banner" onClick={onSetup}><KeyRound/><span><strong>先连接你的 Gemini</strong><small>填写中转地址与本机密钥，选择使用模型</small></span><ChevronRight/></button>}
    <div className="chat-tools"><label>会话范围<select value={scope} onChange={e=>setScope(e.target.value)}><option value={ownerScope}>主人 · 本机私聊</option>{snapshot.conversations.map(c=><option key={c.id} value={c.scope}>{field(c,'title')} {c.reliable?'':'（待识别）'}</option>)}</select></label><span className="pill">{scope===ownerScope?'主人权限':'独立会话记忆'}</span></div>
    <div className="message-list">{messages.length===0?<div className="chat-empty"><span className="empty-mark"><MessageCircle size={28}/></span><h2>从一段对话开始</h2><p>你可以先和 Gemini 一起整理角色设定。<br/>角色正文目前留白，随时可在「角色」里修改和保存版本。</p></div>:messages.map(m=><article key={m.id} className={'chat-message '+field(m,'role')}><small>{field(m,'role')==='user'?'你 / 会话消息':snapshot.persona.name}<span>{field(m,'source')}</span></small><div>{field(m,'text')}</div></article>)}<div ref={end}/></div>
    {active && <div className="task-inline"><Loader2 className="spin" size={16}/>{field(active,'progress')}<button onClick={()=>void run('cancel',{id:active.id}).catch(()=>undefined)}>取消</button></div>}
    {active&&field(active,'partialText')&&<article className="chat-message assistant" aria-live="polite"><small>正在回复</small><div>{field(active,'partialText')}</div></article>}
    {scope===ownerScope&&<DocumentsPanel run={run} onAttach={setObservation}/>}
    {scope===ownerScope?<form className="chat-compose" onSubmit={e=>void send(e)}><label className="sr-only" htmlFor="companion-message">发给助手的消息</label><textarea id="companion-message" placeholder="说点什么，或安排一个任务…" value={draft} onChange={e=>setDraft(e.target.value)} rows={3} maxLength={12000}/><div><select aria-label="附加观察资料" value={observation} onChange={e=>setObservation(e.target.value)}><option value="">不附加资料</option>{snapshot.observations.map(o=><option key={o.id} value={o.id}>{field(o,'videoId')||field(o,'source')} · {o.analyzed?'已分析':'待分析'}</option>)}</select><button className="primary" type="submit" disabled={!draft.trim()||Boolean(active)}>发送 <ChevronRight size={16}/></button></div></form>:<p className="subtle">这里查看该会话的历史；回复草稿与发送状态在「任务」中管理。</p>}
  </section>
}

function Models({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const [settings,setSettings]=useState<Settings>(snapshot.settings),[key,setKey]=useState(''),[saved,setSaved]=useState(false)
  const selected=settings.profiles.find(p=>p.model===settings.activeModel)??settings.profiles[0]
  function editProfile(change:Partial<typeof selected>){setSaved(false);setSettings(s=>({...s,profiles:s.profiles.map(p=>p===selected?{...p,...change}:p),...(change.model?{activeModel:change.model}:{})}))}
  async function save(e:FormEvent){e.preventDefault();try{if(key){await run('saveKey',{key});setKey('')}await run('settings',settings);setSaved(true)}catch{/* shown */}}
  return <section className="content-panel"><Heading title="连接你的模型" text="原生 Google/Gemini 协议。切换模型不会重置人格、会话和长期记忆。"/>
    <DailyBudget snapshot={snapshot} run={run}/>
    <form onSubmit={e=>void save(e)} className="stack">
      <div className="card"><h2>连接信息</h2><label>API 地址<input required type="url" value={settings.endpoint} onChange={e=>setSettings({...settings,endpoint:e.target.value})}/><small>可填根地址或 /v1beta；使用 HTTPS，不在地址中填写密钥。</small></label><label>本机密钥<input type="password" autoComplete="new-password" value={key} placeholder={snapshot.keyConfigured?'已保存；留空保持现有密钥':'仅在此设备填写新密钥'} onChange={e=>setKey(e.target.value)}/><small>保存在 Android Keystore 保护的存储中，不进入备份或模型提示词。</small></label><label className="check-row"><input type="checkbox" checked={settings.networkEnabled} onChange={e=>setSettings({...settings,networkEnabled:e.target.checked})}/>允许向这个中转站发送会话和主动附加的资料</label></div>
      <div className="card"><h2>模型配置</h2><div className="model-picks">{settings.profiles.map(p=><button type="button" key={p.id} className={p.model===settings.activeModel?'active':''} onClick={()=>setSettings({...settings,activeModel:p.model})}><Sparkles size={17}/>{p.model}</button>)}</div><div className="form-grid"><label>实际模型 ID<input value={selected.model} onChange={e=>editProfile({model:e.target.value})}/></label><label>思考等级<select value={selected.thinking} onChange={e=>editProfile({thinking:e.target.value as 'low'|'medium'|'high'})}><option value="low">低 · 较快响应</option><option value="medium">中 · 平衡</option><option value="high">高 · 复杂任务</option></select></label><label>本轮输出预算<input type="number" min={1024} max={262144} value={selected.maxOutputTokens} onChange={e=>editProfile({maxOutputTokens:Number(e.target.value)})}/></label><label>上下文预算<input type="number" min={4096} max={262144} value={selected.contextBudget} onChange={e=>editProfile({contextBudget:Number(e.target.value)})}/></label><label>单次超时（秒）<input type="number" min={30} max={1200} value={selected.timeoutSeconds} onChange={e=>editProfile({timeoutSeconds:Number(e.target.value)})}/></label><label>续写段数上限<input type="number" min={1} max={8} value={selected.maxHops} onChange={e=>editProfile({maxHops:Number(e.target.value)})}/></label></div><div className="capability-row">{(['images','video','audio','pdf','tools'] as const).map(k=><label key={k}><input type="checkbox" checked={selected[k]} onChange={e=>editProfile({[k]:e.target.checked})}/>{{images:'图片',video:'视频',audio:'音频',pdf:'PDF',tools:'工具'}[k]}</label>)}</div><p className="subtle">能力勾选是本机配置，不代表已验证中转支持；遇到协议错误会停止并提示。Argon 使用 thinkingLevel 与有界续写。</p><button type="button" className="text-button" onClick={()=>{const p=profile(`custom-model-${settings.profiles.length+1}`);setSettings({...settings,activeModel:p.model,profiles:[...settings.profiles,p]})}}><Plus size={16}/> 添加自定义模型</button></div>
      <div className="card"><h2>主人与活动各自的额度</h2><p>在「生活」中查看和调整两份独立额度。工具回合、重试与续写逐次预留，不会因活动用尽而挪用主人额度。费用以站点账单为准；未知价格不保证实际金额。</p></div>
      <div className="form-actions"><button className="primary" type="submit">保存配置</button>{saved&&<span className="saved"><Check size={16}/> 已保存</span>}<button type="button" className="text-button" onClick={()=>{if(confirm('删除本机保存的模型密钥？'))void run('clearKey').catch(()=>undefined)}}>清除密钥</button></div>
    </form>
  </section>
}

function PersonaEditor({value,versions,run}:{value:Persona;versions:Entry[];run:Run}) {
  const [name,setName]=useState(value.name),[text,setText]=useState(value.text),[source,setSource]=useState(value.source??'主人编辑'),[note,setNote]=useState('')
  const [extras,setExtras]=useState<Record<string,unknown>>(value.extras??{})
  const book=extras.characterBook as {entries?:unknown[]}|undefined
  const extraSummary=[extras.postHistory?'历史后指令':'',extras.depthPrompt?'深度提示':'',book?.entries?.length?`世界书 ${book.entries.length} 条`:''].filter(Boolean).join('、')
  const file=useRef<HTMLInputElement>(null)
  async function importCard(input:File){try{const card=await readCharacterCard(input);setName(card.name);setText(card.text);setSource(card.source??'导入');setExtras(card.extras??{});setNote('已载入编辑区；检查资料后保存为新版本。')}catch(e){setNote(String(e))}}
  async function exportCard(){const content=JSON.stringify({name,text,source,extras,format:'dsh-companion-persona',version:1},null,2);try{if(nativeCompanion)await run('saveDocument',{name:'小克角色.json',text:content});else downloadText('小克角色.json',content)}catch{/* shown */}}
  return <section className="content-panel"><Heading title="角色，由你定义" text="这里保存你和 Gemini 共同创作的设定。人格正文与事件、关系记忆分开存储。"/>
    <form className="stack" onSubmit={e=>{e.preventDefault();void run('persona',{name,text,source,extras}).then(()=>setNote('已保存为新版本')).catch(()=>undefined)}}>
      <div className="card"><div className="section-row"><h2>当前设定</h2><span className="pill">已保存 v{value.version}</span></div><label>称呼<input value={name} maxLength={40} required onChange={e=>setName(e.target.value)}/></label><label>人格与表达方式<textarea className="persona-input" rows={14} value={text} maxLength={64000} placeholder="暂时留白。接入 Gemini 后，再一起写下你希望的性格、关系定位、表达方式与边界。" onChange={e=>setText(e.target.value)}/></label><label>资料来源 / 版本说明<input value={source} maxLength={1000} onChange={e=>setSource(e.target.value)}/></label><div className="form-actions"><button type="submit" className="primary">保存新版本</button><button type="button" onClick={()=>file.current?.click()}><Upload size={16}/> 导入</button><button type="button" onClick={()=>void exportCard()}><Download size={16}/> 导出</button></div><input ref={file} hidden type="file" accept=".json,.png,application/json,image/png" onChange={e=>{const f=e.target.files?.[0];if(f)void importCard(f);e.target.value=''}}/>{extraSummary&&<p className="subtle">角色卡附带：{extraSummary}（随人设保存，聊天时在最近几条消息旁生效）<button type="button" className="text-button" onClick={()=>setExtras({})}>不用这些</button></p>}{note&&<p role="status" className="subtle">{note}</p>}<small>支持本应用 JSON、Character Card V2/V3 JSON 或 PNG。导入文本不会授予角色额外的账号或设备权限。</small></div>
      <div className="card"><h2>资料入口</h2><p>「小克」是社区二创称呼，没有本应用认定的唯一官方人设。不同画师、卡片与社区梗可能互相冲突；保留出处并按原作者许可使用。</p><div className="reference-links"><a href="https://github.com/Charliechen114514/deslop/blob/main/example/personas/claude-girl.md" target="_blank" rel="noreferrer">claude-girl 社区文本参考 ↗</a><a href="https://github.com/SillyTavern/SillyTavern" target="_blank" rel="noreferrer">角色卡格式与编辑经验 ↗</a><a href="https://www.anthropic.com/claude" target="_blank" rel="noreferrer">Claude 产品资料（不等同二创设定）↗</a></div><small>这些是可查阅资料，不会自动安装、导入或确认为已获插画授权。</small></div>
      <div className="card"><h2>历史版本</h2>{versions.length===0?<p className="subtle">保存后会在这里保留版本，可随时恢复。</p>:versions.slice().reverse().slice(0,20).map(v=><div className="record-row" key={v.id}><div><strong>v{Number(v.version)} · {field(v,'name')}</strong><small>{field(v,'source')}</small></div><button type="button" onClick={()=>void run('restorePersona',{id:v.id}).then(()=>{setName(field(v,'name'));setText(field(v,'text'));setNote('已恢复并生成新版本')}).catch(()=>undefined)}>恢复</button></div>)}</div>
    </form>
  </section>
}

function Tasks({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const [filter,setFilter]=useState('pending')
  const tasks=snapshot.tasks.slice().reverse().filter(t=>filter==='all'||!['done','cancelled'].includes(field(t,'state')))
  return <section className="content-panel"><Heading title="每一步，都有去向" text="先审阅目标和正文，再授权发送。提交结果不明时，请核对平台；系统不会猜测成功或自动重发。"/><div className="section-row"><span className="subtle">{tasks.length} 条记录</span><select aria-label="任务筛选" value={filter} onChange={e=>setFilter(e.target.value)}><option value="pending">待处理</option><option value="all">全部记录</option></select></div><div className="stack">{tasks.length===0?<div className="card empty-state"><Check/><h2>这里暂时没有待处理的事</h2><p>模型任务、QQ 回复和 B站评论草稿会出现在这里。</p></div>:tasks.map(t=><TaskCard key={t.id} task={t} run={run}/>)}</div></section>
}

function TaskCard({task,run}:{task:Entry;run:Run}) {
  const [body,setBody]=useState(field(task,'body')),[account,setAccount]=useState(field(task,'account'));const state=field(task,'state'),isSend=field(task,'type')==='send',isBili=task.package==='tv.danmaku.bili';const edited=body!==field(task,'body')||(isBili&&account!==field(task,'account'))
  const execute=(action:string,data:object={})=>void run(action,{id:task.id,...data}).catch(()=>undefined)
  return <article className="card"><div className="section-row"><h2>{isSend?`${task.package==='tv.danmaku.bili'?'B站评论':'QQ 回复'} · ${field(task,'target')}`:'模型任务'}</h2><span className={'pill state-'+state}>{states[state]??state}</span></div><small className="break-anywhere">{task.scope}</small>
    {isSend&&<label>正文<textarea rows={4} value={body} maxLength={4000} disabled={!['draft','approved'].includes(state)} onChange={e=>setBody(e.target.value)}/></label>}
    {isBili&&<><label>已核对的 B站登录账号标记<input value={account} disabled={!['draft','approved'].includes(state)} maxLength={80} onChange={e=>setAccount(e.target.value)} placeholder="填昵称或 UID，并在平台页面确认"/></label><small>这是你核对后的绑定标记；小克不会从这个输入框推断已登录。</small><details><summary>评论依据与覆盖范围</summary><p>{field(task,'coverage')}</p><p>{field(task,'evidenceSummary')}</p></details></>}
    {field(task,'progress')&&<p className="subtle">{field(task,'progress')}</p>}{field(task,'error')&&<p className="error-text">{field(task,'error')}</p>}
    <div className="form-actions">{isSend&&['draft','approved'].includes(state)&&edited&&<button onClick={()=>execute('editDraft',{body,...(isBili?{account}:{})})}>保存修改并撤销旧授权</button>}
      {isSend&&state==='draft'&&!edited&&<button className="primary" disabled={isBili&&!account.trim()} onClick={()=>{if(confirm(`授权向「${field(task,'target')}」提交当前正文？授权 ${isBili?'五':'两'} 分钟有效，修改正文或账号后需重审。`))execute('approve')}}>授权此草稿</button>}
      {isSend&&task.route!=='onebot'&&['draft','approved'].includes(state)&&<button onClick={()=>execute('prepareUi')}>打开目标，用悬浮窗确认 UI 发送</button>}
      {isSend&&state==='approved'&&task.package==='com.tencent.mobileqq'&&<button className="primary" onClick={()=>execute('send',{route:task.route==='onebot'?'onebot':'notification'})}>{task.route==='onebot'?'通过已绑定消息桥发送':'通过 QQ 通知回复'}</button>}
      {state==='unknown'&&<><button onClick={()=>execute('resolveUnknown',{delivered:true})}>已核对：发送成功</button><button onClick={()=>execute('resolveUnknown',{delivered:false})}>已核对：未发出，结束此任务</button></>}
      {!isSend&&['failed','waiting','cancelled'].includes(state)&&<button onClick={()=>execute('retry')}>手动重试</button>}
      {!['done','cancelled','unknown','sending'].includes(state)&&<button onClick={()=>execute('cancel')}>取消任务</button>}
    </div>{isSend&&<small>UI 发送要求目标会话与输入框可明确辨认；无法确认时停止，不猜测点击位置。</small>}
  </article>
}

function Device({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const [status,setStatus]=useState(''),[password,setPassword]=useState('')
  const permissions=snapshot.permissions
  const execute=(action:string,data:object={})=>void run(action,data).then(result=>setStatus(typeof result==='string'?result:JSON.stringify(result,null,2)??'操作完成')).catch(()=>undefined)
  return <section className="content-panel"><Heading title="让能力，接上你的手机" text="按需要开启权限。QQ 常态使用独立消息桥；B站和设备任务保留真实安卓操作。登录由你完成。"/>
    <div className="stack"><div className="card"><div className="section-row"><h2>权限与运行状态</h2><span className="pill">Android {permissions.androidVersion}</span></div><small>设备 ABI：{permissions.abis.join('、')||'浏览器不提供'}</small><div className="permission-list">{[
      ['accessibility','无障碍服务',permissions.accessibility.serviceStatus==='connected'||permissions.accessibility.serviceStatus==='enabled','读取界面、点击与短时截图'],
      ['notifications','QQ 通知访问',permissions.notificationAccess,'接收系统已展示的 QQ 通知与可用的回复动作'],
      ['overlay','悬浮窗',permissions.overlay,'在应用外显示可控的助手入口'],
    ].map(([kind,name,ok,detail])=><div className="record-row" key={String(kind)}><div><strong>{String(name)} <span className={ok?'saved':'subtle'}>{ok?'已开启':'待开启'}</span></strong><small>{String(detail)}</small></div><button onClick={()=>execute('openPermission',{kind})}>设置</button></div>)}</div><div className="form-actions"><button onClick={()=>execute('notificationPermission')}>允许前台任务通知</button><button onClick={()=>execute('openPermission',{kind:'battery'})}>查看电池限制</button></div><label>DSH 设备控制密码（已设置时填写）<input type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)}/></label><button onClick={()=>void run('grantApps',{password}).then(()=>{setPassword('');setStatus('已加入 QQ、B站应用允许列表')}).catch(()=>undefined)}>允许控制 QQ 与 B站</button><small>息屏、锁屏、受保护画面和厂商省电策略可能中断操作，界面控制不会绕过系统锁屏。</small>
      <label className="check-row"><input type="checkbox" checked={snapshot.settings.backgroundEnabled} onChange={e=>execute('settings',{...snapshot.settings,backgroundEnabled:e.target.checked})}/>后台常驻（显示常驻通知；解锁后她主动找你、重启后自动恢复都需要开启）</label>
      </div><KeepAlive snapshot={snapshot} run={run}/>
    <Connections snapshot={snapshot} run={run}/><div className="card"><h2>QQ 会话 · NapCat 为主通道</h2><button onClick={()=>execute('silenceGroups')}>立即静音全部群</button><label className="check-row"><input type="checkbox" checked={snapshot.settings.qqEnabled??false} onChange={e=>execute('settings',{...snapshot.settings,qqEnabled:e.target.checked})}/>备用：发现 QQ 通知会话，单独授权后才记录正文</label><p>常态收发使用 NapCat，不操作你正在使用的 QQ 界面。通知路线是可选备用入口，群聊文字不能取得本机主人权限。</p><button onClick={()=>execute('openApp',{package:'com.tencent.mobileqq'})}>打开 QQ</button>{snapshot.conversations.length===0?<p className="subtle">连接 NapCat 后可手动绑定会话，或等新消息发现目标。</p>:snapshot.conversations.map(c=><Conversation key={c.id} value={c} run={run}/>)}
    </div><VideoPanel run={run} networkEnabled={snapshot.settings.networkEnabled}/>
    {status&&<div className="card"><div className="section-row"><h2>最近操作结果</h2><button aria-label="清除操作结果" onClick={()=>setStatus('')}><X size={16}/></button></div><pre className="operation-result">{status}</pre></div>}
    <div className="card"><h2>观察资料</h2>{snapshot.observations.length===0?<p className="subtle">暂无资料。采集或导入后，在对话中附加进行分析。</p>:snapshot.observations.slice().reverse().map(o=><article className="memory-record" key={o.id}><div className="section-row"><strong>{field(o,'videoId')||field(o,'source')}{o.temporary?' · 临时未保存':''}</strong><div className="inline-actions">{Boolean(o.temporary)&&<button onClick={()=>execute('saveObservation',{id:o.id})}>保存资料</button>}{Boolean(o.analyzed)&&<button onClick={()=>{if(confirm('将已核对的分析存为这个会话范围的事件记忆？'))execute('rememberObservation',{id:o.id})}}>存为事件记忆</button>}<button onClick={()=>{if(confirm('删除这份观察资料和原始媒体？'))execute('deleteObservation',{id:o.id})}}>删除资料</button></div></div><p>{field(o,'coverage')}</p><small>{o.analyzed?'已取得模型分析':'尚未发给模型'} · {field(o,'source')}</small>{Boolean(o.analyzed)&&<details><summary>查看分析</summary><p>{field(o,'analysis')}</p></details>}</article>)}</div></div>
  </section>
}

function Conversation({value,run}:{value:Entry;run:Run}) {
  const [draft,setDraft]=useState(''),[capture,setCapture]=useState(Boolean(value.captureEnabled)),[night,setNight]=useState(value.nightQuiet!==false),[images,setImages]=useState(Boolean(value.allowImages))
  const [mode,setMode]=useState(field(value,'mode')||'quiet'),[cooldown,setCooldown]=useState(Number(value.cooldownSeconds)||60),[hourly,setHourly]=useState(Number(value.hourlyLimit)||10),[auto,setAuto]=useState(Boolean(value.autoReply)),[saved,setSaved]=useState(false)
  return <form className="conversation-card" onSubmit={e=>{e.preventDefault();void run('conversation',{id:value.id,mode,cooldownSeconds:cooldown,hourlyLimit:hourly,autoReply:auto,threshold:0.65,captureEnabled:capture,nightQuiet:night,allowImages:images,retentionDays:30}).then(()=>setSaved(true)).catch(()=>undefined)}}><div className="section-row"><strong>{field(value,'title')}</strong><span className="pill">{value.reliable?'系统会话已识别':'待识别 · 不能自动回复'}</span></div><label className="check-row"><input type="checkbox" checked={capture} onChange={e=>setCapture(e.target.checked)}/>授权接收并记录此会话（未勾选时丢弃正文）</label><label className="check-row"><input type="checkbox" checked={night} onChange={e=>setNight(e.target.checked)}/>00:00–08:00 群聊仅响应已绑定主人 @</label><label className="check-row"><input type="checkbox" checked={images} onChange={e=>setImages(e.target.checked)}/>允许读取本会话图片并交给模型理解</label><small>群历史与记忆默认保留 30 天；长期主人记忆不受此期限影响。</small><div className="form-grid"><label>参与方式<select value={mode} disabled={!value.reliable} onChange={e=>{setMode(e.target.value);setSaved(false)}}><option value="quiet">安静 · 只记录</option><option value="mention">提到助手时回复</option><option value="natural">按话题自然参与</option></select></label><label>最短间隔（秒）<input type="number" min={10} max={86400} value={cooldown} onChange={e=>setCooldown(Number(e.target.value))}/></label><label>每小时最多回复<input type="number" min={1} max={60} value={hourly} onChange={e=>setHourly(Number(e.target.value))}/></label></div><label className="check-row"><input type="checkbox" checked={auto} disabled={!value.reliable||mode==='quiet'} onChange={e=>setAuto(e.target.checked)}/>在此会话范围内自动提交回复（未勾选时只生成待审草稿）</label><div className="form-actions"><button type="submit">保存会话范围</button><button type="button" onClick={()=>{if(confirm('清空此会话的消息、记忆与任务，并关闭接收？'))void run('clearConversation',{scope:value.scope}).catch(()=>undefined)}}>清空此会话</button>{saved&&<span className="saved">已保存</span>}</div><label>给此会话写一条回复<textarea rows={3} value={draft} onChange={e=>setDraft(e.target.value)} maxLength={4000}/></label><button type="button" disabled={!draft.trim()} onClick={()=>void run('draftReply',{scope:value.scope,body:draft}).then(()=>setDraft('')).catch(()=>undefined)}>生成待审草稿，在任务页授权</button></form>
}
