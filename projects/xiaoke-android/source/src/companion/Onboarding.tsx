import { useState } from 'react'
import { ArrowLeft, Check, ChevronRight } from 'lucide-react'
import { nativeCompanion, type Snapshot } from './bridge'
import { KeepAlive } from './KeepAlive'
import { DailyBudget } from './DailyBudget'
import { QqConnection } from './Connections'

type Run = (action: string, data?: object) => Promise<unknown>

/** First launch: she is already 小克 (persona bundled); the owner connects a model, keeps her alive and optionally links QQ. */
export function Onboarding({snapshot, run, onClose}: {snapshot: Snapshot; run: Run; onClose: () => void; onOpen?: (tab: 'models'|'persona'|'device'|'memory') => void}) {
  const [step, setStep] = useState(0), [endpoint, setEndpoint] = useState(snapshot.settings.endpoint)
  const [model, setModel] = useState(snapshot.settings.activeModel), [key, setKey] = useState('')
  const [saving, setSaving] = useState(false), [note, setNote] = useState('')
  const titles = ['连上模型', '每天的额度', '让她一直在', 'QQ（可以跳过）']
  async function saveModel() {
    setSaving(true); setNote('')
    try {
      await run('settings', {...snapshot.settings, endpoint, activeModel: model, networkEnabled: true})
      if (key.trim()) { await run('saveKey', {key: key.trim()}); setKey('') }
      setNote('已保存。回到聊天页说句话就能试试。'); setStep(1)
    } catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  async function finish() {
    setSaving(true)
    try { await run('settings', {...snapshot.settings, onboardingCompleted: true}); onClose() }
    catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  return <section className="content-panel onboarding">
    <div className="companion-heading"><span className="pill">{step + 1} / {titles.length}</span><h1>{titles[step]}</h1>
      <p>{snapshot.persona.text ? `${snapshot.persona.name}已经在了，人设在“角色”页可以看和改。` : '还没有人设，可以在“角色”页导入角色卡。'}</p></div>
    <ol className="onboarding-steps" aria-label="设置进度">{titles.map((title, i) => <li key={title}><button aria-current={i === step ? 'step' : undefined} className={i === step ? 'selected' : ''} onClick={() => setStep(i)}>{i < step ? <Check size={15}/> : i + 1} {title}</button></li>)}</ol>
    {step === 0 && <div className="card">
      <label>API 地址<input value={endpoint} onChange={e => setEndpoint(e.target.value)} inputMode="url" autoCapitalize="none" spellCheck={false}/></label>
      <label>日常模型<select value={model} onChange={e => setModel(e.target.value)}>{snapshot.settings.profiles.map(p => <option key={p.model}>{p.model}</option>)}</select></label>
      <label>API Key<input type="password" autoComplete="new-password" value={key} disabled={!nativeCompanion} onChange={e => setKey(e.target.value)} placeholder={snapshot.keyConfigured ? '已保存，留空不变' : '你的中转密钥'}/></label>
      <button className="primary" disabled={saving || !nativeCompanion} onClick={() => void saveModel()}>保存并联网</button>
    </div>}
    {step === 1 && <DailyBudget snapshot={snapshot} run={run}/>}
    {step === 2 && <KeepAlive snapshot={snapshot} run={run}/>}
    {step === 3 && <QqConnection snapshot={snapshot} run={run}/>}
    {note && <p role="status">{note}</p>}
    <div className="form-actions onboarding-actions">
      <button disabled={step === 0 || saving} onClick={() => setStep(step - 1)}><ArrowLeft size={16}/> 上一步</button>
      {step < titles.length - 1
        ? <button className="primary" disabled={saving} onClick={() => setStep(step + 1)}>下一步 <ChevronRight size={16}/></button>
        : <button className="primary" disabled={saving} onClick={() => void finish()}>开始聊天</button>}
    </div>
  </section>
}
