import { useState } from 'react'
import { ArrowLeft, ArrowRight, Check } from '@phosphor-icons/react'
import { nativeCompanion, profile, type Run, type Snapshot } from './bridge'
import { KeepAlive, PhoneAccess } from './KeepAlive'
import { DailyBudget } from './DailyBudget'
import { QqConnection } from './Connections'
import { ClaudePanel } from './ClaudePanel'
import { RuntimeStep } from './RuntimeStep'

/**
 * First launch: she is already 小克 (persona bundled). In order: runtime and Claude Code, Claude login, Shizuku; then
 * Gemini (other groups, her own activity, and the fallback), budget, keep-alive and QQ.
 */
const GEMINI = 3

export function Onboarding({snapshot, run, onClose}: {snapshot: Snapshot; run: Run; onClose: () => void}) {
  const [step, setStep] = useState(0), [endpoint, setEndpoint] = useState(snapshot.settings.endpoint)
  const [model, setModel] = useState(snapshot.settings.activeModel), [key, setKey] = useState('')
  const [saving, setSaving] = useState(false), [note, setNote] = useState('')
  const name = snapshot.persona.name || '小克'
  const titles = ['运行环境和 Claude Code', '登录 Claude', '授权 Shizuku', '连上 Gemini', '每天能用多少', '让她一直在', '连上 QQ']
  const lines = [`${name}已经在了。和你私聊、帮你做事由 Claude 来，先装好它要用的环境。`, '用你自己的 Claude 订阅登录，登录过程由 Claude Code 自己完成。',
    '她帮你操作手机、后台玩游戏要用 Shizuku；悬浮窗、无障碍、麦克风用到哪个开哪个。', `其他群、${name}自己逛、后台的事用 Gemini；Claude 用不了时也由它顶上。`,
    '她自己逛和陪你聊天分开算，互不挤占。', '国产系统会清后台，这几步能让她留下来。', '不用 QQ 可以直接跳过。']
  async function saveModel() {
    setSaving(true); setNote('')
    try {
      const profiles = snapshot.settings.profiles.some(p => p.model === model) ? snapshot.settings.profiles : [...snapshot.settings.profiles, profile(model)]
      await run('settings', {...snapshot.settings, endpoint, activeModel: model, profiles, networkEnabled: true})
      if (key.trim()) { await run('saveKey', {key: key.trim()}); setKey('') }
      // A real request, so a wrong address or key shows up here and not as her silence later.
      setNote('正在试着连一下…')
      const test = await run('testModel') as {ok?: boolean; seconds?: number; error?: string; detail?: string} | undefined
      if (test?.ok) { setNote(`连上了（${model}，${Math.round(test.seconds ?? 0)} 秒）`); setStep(GEMINI + 1) }
      else setNote(`没连上：${test?.error ?? '未知原因'}${test?.detail ? '\n' + test.detail : ''}`)
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
        {step === 0 && <><RuntimeStep/><ClaudePanel run={run} only="install"/></>}
        {step === 1 && <ClaudePanel run={run} only="login"/>}
        {step === 2 && <PhoneAccess snapshot={snapshot} run={run}/>}
        {step === GEMINI && <div className="xk-card">
          <label>API 地址<input value={endpoint} onChange={e => setEndpoint(e.target.value)} inputMode="url" autoCapitalize="none" spellCheck={false}/></label>
          <label>日常模型<input value={model} list="xk-models" onChange={e => setModel(e.target.value.trim())} autoCapitalize="none" spellCheck={false}/>
            <datalist id="xk-models">{snapshot.settings.profiles.map(p => <option key={p.model} value={p.model}/>)}</datalist></label>
          <label>密钥<input type="password" autoComplete="new-password" value={key} disabled={!nativeCompanion} onChange={e => setKey(e.target.value)} placeholder={snapshot.keyConfigured ? '已保存，留空不变' : '你的中转密钥'}/></label>
          <button className="xk-btn primary" disabled={saving || !nativeCompanion} onClick={() => void saveModel()}>保存并联网</button>
        </div>}
        {step === 4 && <DailyBudget snapshot={snapshot} run={run}/>}
        {step === 5 && <KeepAlive snapshot={snapshot} run={run}/>}
        {step === 6 && <QqConnection snapshot={snapshot} run={run}/>}
        {note && <p role="status" className={note.startsWith('没连上') ? 'xk-error-text' : 'xk-hint'} style={{whiteSpace: 'pre-wrap'}}>{note}</p>}
      </div>
    </div></div>
    <div className="xk-onboarding-foot">
      {step > 0 && <button className="xk-btn" disabled={saving} onClick={() => setStep(step - 1)}><ArrowLeft size={18} weight="bold"/> 上一步</button>}
      {step < titles.length - 1
        ? <button className="xk-btn primary" disabled={saving} onClick={() => setStep(step + 1)}>{step === GEMINI && !snapshot.keyConfigured ? '先跳过' : '下一步'} <ArrowRight size={18} weight="bold"/></button>
        : <button className="xk-btn primary" disabled={saving} onClick={() => void finish()}><Check size={18} weight="bold"/> 开始聊天</button>}
    </div>
  </div>
}
