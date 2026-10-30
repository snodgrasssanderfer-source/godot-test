import { useState } from 'react'
import { ArrowLeft, ArrowRight, Check } from '@phosphor-icons/react'
import { nativeCompanion, profile, type Run, type Snapshot } from './bridge'
import { KeepAlive } from './KeepAlive'
import { DailyBudget } from './DailyBudget'
import { QqConnection } from './Connections'

/** First launch: she is already 小克 (persona bundled); the owner connects a model, keeps her alive and optionally links QQ. */
export function Onboarding({snapshot, run, onClose}: {snapshot: Snapshot; run: Run; onClose: () => void}) {
  const [step, setStep] = useState(0), [endpoint, setEndpoint] = useState(snapshot.settings.endpoint)
  const [model, setModel] = useState(snapshot.settings.activeModel), [key, setKey] = useState('')
  const [saving, setSaving] = useState(false), [note, setNote] = useState('')
  const name = snapshot.persona.name || '小克'
  const titles = ['先连上模型', '每天能用多少', '让她一直在', '连上 QQ']
  const lines = [`${name}已经在了，连上模型她就能说话。`, '她自己逛和陪你聊天分开算，互不挤占。', '国产系统会清后台，这几步能让她留下来。', '不用 QQ 可以直接跳过。']
  async function saveModel() {
    setSaving(true); setNote('')
    try {
      const profiles = snapshot.settings.profiles.some(p => p.model === model) ? snapshot.settings.profiles : [...snapshot.settings.profiles, profile(model)]
      await run('settings', {...snapshot.settings, endpoint, activeModel: model, profiles, networkEnabled: true})
      if (key.trim()) { await run('saveKey', {key: key.trim()}); setKey('') }
      setStep(1)
    } catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  async function finish() {
    setSaving(true)
    try { await run('settings', {...snapshot.settings, onboardingCompleted: true}); onClose() }
    catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  return <div className="xk-onboarding" role="dialog" aria-label="开始使用">
    <div className="xk-onboarding-body"><div className="xk-onboarding-inner">
      <ol className="xk-steps" aria-label="设置进度">{titles.map((title, i) => <li key={title}><button aria-label={title} aria-current={i === step ? 'step' : undefined} className={i < step ? 'done' : ''} onClick={() => setStep(i)}/></li>)}</ol>
      <div className="xk-step" key={step}>
        <small>{step + 1} / {titles.length}</small>
        <h1>{titles[step]}</h1>
        <p className="xk-hint">{lines[step]}</p>
        {step === 0 && <div className="xk-card">
          <label>API 地址<input value={endpoint} onChange={e => setEndpoint(e.target.value)} inputMode="url" autoCapitalize="none" spellCheck={false}/></label>
          <label>日常模型<input value={model} list="xk-models" onChange={e => setModel(e.target.value.trim())} autoCapitalize="none" spellCheck={false}/>
            <datalist id="xk-models">{snapshot.settings.profiles.map(p => <option key={p.model} value={p.model}/>)}</datalist></label>
          <label>密钥<input type="password" autoComplete="new-password" value={key} disabled={!nativeCompanion} onChange={e => setKey(e.target.value)} placeholder={snapshot.keyConfigured ? '已保存，留空不变' : '你的中转密钥'}/></label>
          <button className="xk-btn primary" disabled={saving || !nativeCompanion} onClick={() => void saveModel()}>保存并联网</button>
        </div>}
        {step === 1 && <DailyBudget snapshot={snapshot} run={run}/>}
        {step === 2 && <KeepAlive snapshot={snapshot} run={run}/>}
        {step === 3 && <QqConnection snapshot={snapshot} run={run}/>}
        {note && <p role="status" className="xk-error-text">{note}</p>}
      </div>
    </div></div>
    <div className="xk-onboarding-foot">
      {step > 0 && <button className="xk-btn" disabled={saving} onClick={() => setStep(step - 1)}><ArrowLeft size={18} weight="bold"/> 上一步</button>}
      {step < titles.length - 1
        ? <button className="xk-btn primary" disabled={saving} onClick={() => setStep(step + 1)}>{step === 0 && !snapshot.keyConfigured ? '先跳过' : '下一步'} <ArrowRight size={18} weight="bold"/></button>
        : <button className="xk-btn primary" disabled={saving} onClick={() => void finish()}><Check size={18} weight="bold"/> 开始聊天</button>}
    </div>
  </div>
}
