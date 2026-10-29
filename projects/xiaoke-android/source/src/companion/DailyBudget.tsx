import { useState } from 'react'
import { nativeCompanion, type Snapshot } from './bridge'

type Run = (action: string, data?: object) => Promise<unknown>
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
  const used = (lane?: Lane) => budgets.configured ? `今天已用 ${Number(lane?.spent ?? 0) + Number(lane?.held ?? 0)} / ${lane?.limit ?? 0}` : '还没设置'
  return <div className="card"><h2>每日额度</h2>
    <p className="subtle">按模型调用次数算。她自己逛群、看视频、写日记用活动额度；和你聊天用你的额度，两边互不挤占。{budgets.configured && budgets.unit !== 'calls' ? '当前是按金额计的高级设置，保存后会改成按次数。' : ''}</p>
    <div className="form-grid">
      <label>她的活动 · 每天<input type="number" min={1} max={10000} value={activity} onChange={e => setActivity(Number(e.target.value))}/><small>{used(budgets.activity)}</small></label>
      <label>陪你聊天 · 每天<input type="number" min={1} max={10000} value={owner} onChange={e => setOwner(Number(e.target.value))}/><small>{used(budgets.owner)}</small></label>
    </div>
    <button disabled={!nativeCompanion || !snapshot.modules?.life.available} onClick={() => void save()}>保存</button>
    {note && <p role="status">{note}</p>}
  </div>
}
