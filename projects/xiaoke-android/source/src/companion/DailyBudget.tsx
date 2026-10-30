import { useState } from 'react'
import { nativeCompanion, type Run, type Snapshot } from './bridge'

type Lane = {limit?: number; spent?: number; held?: number; remaining?: number}

/** Two numbers per day: how many model calls her own activity may use, and how many are kept for chatting with the owner. */
export function DailyBudget({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const budgets = (snapshot.modules?.life.budgets ?? {}) as {configured?: boolean; daily?: boolean; unit?: string; owner?: Lane; activity?: Lane}
  const [owner, setOwner] = useState(budgets.configured ? Number(budgets.owner?.limit ?? 300) : 300)
  const [activity, setActivity] = useState(budgets.configured ? Number(budgets.activity?.limit ?? 60) : 60)
  const [note, setNote] = useState('')
  async function save() {
    try {
      await run('module', {domain: 'life', action: 'budget.configure', data: {unit: 'calls', currency: '', ownerLimit: owner, activityLimit: activity,
        warningRemaining: Math.min(2, activity), allowUnknownPrice: true, unknownPriceCeiling: 1, daily: true}})
      setNote('已保存，每天零点重新计数。')
    } catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
  }
  const lane = (title: string, value: number, set: (n: number) => void, used?: Lane) => {
    const spent = Number(used?.spent ?? 0) + Number(used?.held ?? 0), limit = Number(used?.limit ?? 0)
    return <label className="xk-budget">{title}
      <input type="number" min={1} max={10000} value={value} onChange={e => set(Number(e.target.value))}/>
      {budgets.configured && <><span className="xk-meter"><span style={{width: `${limit ? Math.min(100, spent / limit * 100) : 0}%`}}/></span><small>今天用了 {spent} / {limit}</small></>}
    </label>
  }
  return <div className="xk-card">
    <div className="xk-card-head"><h2>每日额度</h2><small>按模型调用次数</small></div>
    <div className="xk-grid">
      {lane('陪你聊天', owner, setOwner, budgets.owner)}
      {lane('她自己活动', activity, setActivity, budgets.activity)}
    </div>
    {budgets.configured && budgets.unit !== 'calls' && <p className="xk-hint">现在是按金额计的高级设置，保存后会改成按次数。</p>}
    <button type="button" className="xk-btn" disabled={!nativeCompanion || !snapshot.modules?.life.available} onClick={() => void save()}>保存额度</button>
    {note && <p role="status" className="xk-hint">{note}</p>}
  </div>
}
