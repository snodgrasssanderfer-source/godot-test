import { useRef, useState, type FormEvent } from 'react'
import { ArrowCounterClockwise, CaretRight, Check, CircleHalf, Compass, DownloadSimple, Moon, Plus, Sparkle, Sun, Terminal, Trash, UploadSimple } from '@phosphor-icons/react'
import { field, nativeCompanion, profile, type Entry, type Persona, type Run, type Settings, type Snapshot } from './bridge'
import { downloadText, readCharacterCard } from './characterCard'
import { DailyBudget } from './DailyBudget'
import { readThemeMode, saveThemeMode, type ThemeMode } from '../theme'
import { Avatar, PageHeader, Section, Segmented } from './ui'

type Pane = 'model' | 'persona' | 'more'

export function SettingsPage({snapshot, run, onSetup, onWorkbench}: {snapshot: Snapshot; run: Run; onSetup: () => void; onWorkbench: () => void}) {
  const [pane, setPane] = useState<Pane>('model')
  const name = snapshot.persona.name || '小克'
  return <div className="xk-scroll"><div className="xk-page">
    <PageHeader title="我的"/>
    <div className="xk-hero">
      <Avatar name={name} size={64} live={snapshot.settings.networkEnabled && !snapshot.settings.paused}/>
      <div><strong>{name}</strong><small>{snapshot.settings.activeModel}{snapshot.persona.version ? ` · 人设 v${snapshot.persona.version}` : ''}</small></div>
    </div>
    <Segmented label="设置分类" value={pane} onChange={setPane} options={[['model', '模型'], ['persona', '人设'], ['more', '其他']]}/>
    <div className="xk-pane" key={pane}>
      {pane === 'model' && <Models snapshot={snapshot} run={run}/>}
      {pane === 'persona' && <PersonaEditor value={snapshot.persona} versions={snapshot.personaVersions} run={run}/>}
      {pane === 'more' && <More onSetup={onSetup} onWorkbench={onWorkbench}/>}
    </div>
  </div></div>
}

function Models({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const [settings, setSettings] = useState<Settings>(snapshot.settings), [key, setKey] = useState(''), [saved, setSaved] = useState(false)
  const selected = settings.profiles.find(p => p.model === settings.activeModel) ?? settings.profiles[0]
  function editProfile(change: Partial<typeof selected>) { setSaved(false); setSettings(s => ({...s, profiles: s.profiles.map(p => p === selected ? {...p, ...change} : p), ...(change.model ? {activeModel: change.model} : {})})) }
  async function save(e: FormEvent) { e.preventDefault(); try { if (key) { await run('saveKey', {key}); setKey('') } await run('settings', settings); setSaved(true) } catch { /* toast */ } }
  return <div className="xk-stack"><form onSubmit={e => void save(e)} className="xk-stack">
    <div className="xk-card">
      <h2>连接</h2>
      <label>API 地址<input required type="url" value={settings.endpoint} onChange={e => setSettings({...settings, endpoint: e.target.value})} autoCapitalize="none" spellCheck={false}/></label>
      <label>密钥<input type="password" autoComplete="new-password" value={key} placeholder={snapshot.keyConfigured ? '已保存，留空不变' : '你的中转密钥'} onChange={e => setKey(e.target.value)}/></label>
      <label className="xk-switch-row"><span>联网</span><input type="checkbox" checked={settings.networkEnabled} onChange={e => setSettings({...settings, networkEnabled: e.target.checked})}/></label>
    </div>
    <div className="xk-card">
      <h2>模型</h2>
      <div className="xk-chips">{settings.profiles.map(p => <button type="button" key={p.id} className={'xk-chip' + (p.model === settings.activeModel ? ' selected' : '')} onClick={() => setSettings({...settings, activeModel: p.model})}><Sparkle size={15} weight={p.model === settings.activeModel ? 'fill' : 'duotone'}/>{p.model}</button>)}
        <button type="button" className="xk-chip ghost" onClick={() => { const p = profile(`custom-model-${settings.profiles.length + 1}`); setSettings({...settings, activeModel: p.model, profiles: [...settings.profiles, p]}) }}><Plus size={15} weight="bold"/> 添加</button></div>
      <label>模型 ID<input value={selected.model} onChange={e => editProfile({model: e.target.value})} autoCapitalize="none" spellCheck={false}/></label>
      <label>思考<select value={selected.thinking} onChange={e => editProfile({thinking: e.target.value as 'low' | 'medium' | 'high'})}><option value="low">快一点</option><option value="medium">平衡</option><option value="high">想深一点</option></select></label>
      <details className="xk-more"><summary>高级</summary>
        <div className="xk-grid">
          <label>单轮输出<input type="number" min={1024} max={262144} value={selected.maxOutputTokens} onChange={e => editProfile({maxOutputTokens: Number(e.target.value)})}/></label>
          <label>上下文<input type="number" min={4096} max={262144} value={selected.contextBudget} onChange={e => editProfile({contextBudget: Number(e.target.value)})}/></label>
          <label>超时（秒）<input type="number" min={30} max={1200} value={selected.timeoutSeconds} onChange={e => editProfile({timeoutSeconds: Number(e.target.value)})}/></label>
          <label>续写段数<input type="number" min={1} max={8} value={selected.maxHops} onChange={e => editProfile({maxHops: Number(e.target.value)})}/></label>
        </div>
        <div className="xk-chips">{(['images', 'video', 'audio', 'pdf', 'tools'] as const).map(k => <label key={k} className="xk-check-chip"><input type="checkbox" checked={selected[k]} onChange={e => editProfile({[k]: e.target.checked})}/>{{images: '图片', video: '视频', audio: '音频', pdf: 'PDF', tools: '工具'}[k]}</label>)}</div>
      </details>
    </div>
    <div className="xk-actions sticky">
      <button className="xk-btn primary" type="submit">{saved ? <><Check size={18} weight="bold"/> 已保存</> : '保存'}</button>
      <button type="button" className="xk-btn ghost danger" onClick={() => { if (confirm('删除本机保存的模型密钥？')) void run('clearKey').catch(() => undefined) }}><Trash size={18} weight="duotone"/> 清除密钥</button>
    </div>
  </form><DailyBudget snapshot={snapshot} run={run}/></div>
}

function PersonaEditor({value, versions, run}: {value: Persona; versions: Entry[]; run: Run}) {
  const [name, setName] = useState(value.name), [text, setText] = useState(value.text), [source, setSource] = useState(value.source ?? '主人编辑'), [note, setNote] = useState('')
  const [extras, setExtras] = useState<Record<string, unknown>>(value.extras ?? {})
  const [aliases, setAliases] = useState((value.aliases ?? []).join('、'))
  const book = extras.characterBook as {entries?: unknown[]} | undefined
  const extraSummary = [extras.postHistory ? '历史后指令' : '', extras.depthPrompt ? '深度提示' : '', book?.entries?.length ? `世界书 ${book.entries.length} 条` : ''].filter(Boolean).join('、')
  const file = useRef<HTMLInputElement>(null)
  async function importCard(input: File) { try { const card = await readCharacterCard(input); setName(card.name); setText(card.text); setSource(card.source ?? '导入'); setExtras(card.extras ?? {}); setNote('已载入，确认后保存。') } catch (e) { setNote(String(e)) } }
  async function exportCard() { const content = JSON.stringify({name, text, source, extras, format: 'dsh-companion-persona', version: 1}, null, 2); try { if (nativeCompanion) await run('saveDocument', {name: '小克角色.json', text: content}); else downloadText('小克角色.json', content) } catch { /* toast */ } }
  return <form className="xk-stack" onSubmit={e => { e.preventDefault(); void run('persona', {name, text, source, extras, aliases: aliases.split(/[\s,，、]+/).filter(Boolean)}).then(() => setNote('已保存为新版本')).catch(() => undefined) }}>
    <div className="xk-card">
      <div className="xk-card-head"><h2>人设</h2><span className="xk-pill">v{value.version}</span></div>
      <label>称呼<input value={name} maxLength={40} required onChange={e => setName(e.target.value)}/></label>
      <label>别名<input value={aliases} maxLength={200} placeholder="群里叫这些也算叫她，用顿号隔开" onChange={e => setAliases(e.target.value)}/></label>
      <label>正文<textarea className="xk-persona-input" rows={12} value={text} maxLength={64000} placeholder="她是谁、怎么说话、和你是什么关系…" onChange={e => setText(e.target.value)}/></label>
      <label>备注<input value={source} maxLength={1000} onChange={e => setSource(e.target.value)}/></label>
      {extraSummary && <p className="xk-hint">角色卡附带：{extraSummary} <button type="button" className="xk-link" onClick={() => setExtras({})}>不用这些</button></p>}
      {note && <p role="status" className="xk-hint">{note}</p>}
      <div className="xk-actions">
        <button type="submit" className="xk-btn primary">保存新版本</button>
        <button type="button" className="xk-btn" onClick={() => file.current?.click()}><UploadSimple size={18} weight="duotone"/> 导入角色卡</button>
        <button type="button" className="xk-btn" onClick={() => void exportCard()}><DownloadSimple size={18} weight="duotone"/> 导出</button>
      </div>
      <input ref={file} hidden type="file" accept=".json,.png,application/json,image/png,*/*" onChange={e => { const f = e.target.files?.[0]; if (f) void importCard(f); e.target.value = '' }}/>
    </div>
    <Section icon={<ArrowCounterClockwise size={20} weight="duotone"/>} title="历史版本" summary={versions.length ? `${versions.length} 个版本` : '还没有'}>
      {versions.length === 0 ? <p className="xk-hint">保存后会留在这里，随时能恢复。</p> : versions.slice().reverse().slice(0, 20).map(v => <div className="xk-row" key={v.id}>
        <span><strong>v{Number(v.version)} · {field(v, 'name')}</strong><small>{field(v, 'source')}</small></span>
        <button type="button" className="xk-btn small" onClick={() => void run('restorePersona', {id: v.id}).then(() => { setName(field(v, 'name')); setText(field(v, 'text')); setNote('已恢复') }).catch(() => undefined)}>恢复</button>
      </div>)}
    </Section>
  </form>
}

function More({onSetup, onWorkbench}: {onSetup: () => void; onWorkbench: () => void}) {
  const [theme, setTheme] = useState<ThemeMode>(readThemeMode())
  return <div className="xk-stack">
    <div className="xk-card">
      <h2>外观</h2>
      <div className="xk-theme-picks">{([['system', '跟随系统', CircleHalf], ['light', '浅色', Sun], ['dark', '深色', Moon]] as const).map(([mode, label, Icon]) =>
        <button type="button" key={mode} className={'xk-theme-pick' + (theme === mode ? ' selected' : '')} aria-pressed={theme === mode} onClick={() => { if (saveThemeMode(mode)) setTheme(mode) }}><Icon size={24} weight={theme === mode ? 'fill' : 'duotone'}/>{label}</button>)}</div>
    </div>
    <div className="xk-card xk-list">
      <button type="button" className="xk-row-btn" onClick={onSetup}><Compass size={22} weight="duotone"/><span><strong>重新走一遍引导</strong><small>模型、额度、保活、QQ</small></span><CaretRight size={18} weight="bold"/></button>
      <button type="button" className="xk-row-btn" onClick={onWorkbench}><Terminal size={22} weight="duotone"/><span><strong>DSH 工作台</strong><small>文件、终端、插件</small></span><CaretRight size={18} weight="bold"/></button>
    </div>
  </div>
}
