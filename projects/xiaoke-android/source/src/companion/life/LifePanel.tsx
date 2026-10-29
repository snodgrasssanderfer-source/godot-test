import { useEffect, useRef, useState } from 'react'
import type { ModulePanelProps } from '../iteration/contracts'
import './life.css'

type Entry = Record<string, unknown>
interface LifePanelProps extends ModulePanelProps { scopes?: {id:string;label:string}[]; saveFile?: (name: string, encryptedText: string) => Promise<unknown> }
const object = (value: unknown): Entry => value && typeof value === 'object' && !Array.isArray(value) ? value as Entry : {}
const entries = (value: unknown): Entry[] => Array.isArray(value) ? value.map(object) : []
const text = (value: unknown) => typeof value === 'string' ? value : ''
const number = (value: unknown) => typeof value === 'number' ? value : 0
const date = (value: unknown) => number(value) ? new Date(number(value)).toLocaleString() : '时间未记录'

/** Real host commands only. Root imports this optional panel and reloads module state after changes. */
export function LifePanel({ state, run, available, saveFile, scopes }: LifePanelProps) {
  const [tab, setTab] = useState<'memory' | 'diary' | 'interest' | 'budget' | 'attention' | 'backup'>('memory')
  const [query, setQuery] = useState('')
  const [scope, setScope] = useState('owner:local')
  const [showForgotten, setShowForgotten] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [found, setFound] = useState<Entry[] | null>(null)
  const [editing, setEditing] = useState<Entry | null>(null)
  const [draft, setDraft] = useState('')
  const [factKey, setFactKey] = useState('')
  const [kind, setKind] = useState('fact')
  const [history, setHistory] = useState<Entry[] | null>(null)
  const [deleteId, setDeleteId] = useState('')
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const importFile = useRef<HTMLInputElement>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [unit, setUnit] = useState('calls')
  const [currency, setCurrency] = useState('CNY')
  const [ownerLimit, setOwnerLimit] = useState('80')
  const [activityLimit, setActivityLimit] = useState('20')
  const [warning, setWarning] = useState('2')
  const [unknown, setUnknown] = useState(false)
  const [ceiling, setCeiling] = useState('1')
  const [minutes, setMinutes] = useState('30')
  const [password, setPassword] = useState('')
  const [backup, setBackup] = useState('')
  const [restoreConfirm, setRestoreConfirm] = useState(false)
  const budgets = object(state.budgets)
  const attention = object(state.attention)
  const configured = Boolean(budgets.configured)
  const configuredUnit = text(budgets.unit) || 'calls'
  const configuredCurrency = text(budgets.currency) || 'CNY'
  const configuredOwner = String(number(object(budgets.owner).limit))
  const configuredActivity = String(number(object(budgets.activity).limit))
  const configuredWarning = String(number(budgets.warningRemaining))
  const configuredUnknown = Boolean(object(budgets.unknownPrice).allowed)
  const configuredCeiling = String(number(object(budgets.unknownPrice).ceiling))

  useEffect(() => {
    setHistory(null)
    if (!lock.current) setFound(null)
  }, [state.contentEpoch])
  useEffect(() => {
    if (!configured) return
    setUnit(configuredUnit); setCurrency(configuredCurrency)
    setOwnerLimit(configuredOwner); setActivityLimit(configuredActivity)
    setWarning(configuredWarning); setUnknown(configuredUnknown); setCeiling(configuredCeiling)
  }, [configured, configuredUnit, configuredCurrency, configuredOwner, configuredActivity, configuredWarning, configuredUnknown, configuredCeiling])

  async function act(action: string, data: Entry = {}, done?: (result: Entry) => void, refreshRecords = false) {
    if (!available || lock.current) return
    lock.current = true; setBusy(true); setError(''); setNotice('')
    let saved = false
    try {
      const result = object(await run(action, data)); saved = true; done?.(result)
      if (refreshRecords) setFound(entries(object(await run(listAction(), searchData())).items))
      setNotice('操作完成')
    }
    catch (cause) { setError((saved ? '内容已保存，但列表刷新失败，请重新搜索。' : '') + (cause instanceof Error ? cause.message : '操作未完成')) }
    finally { lock.current = false; setBusy(false) }
  }
  function select(next: typeof tab) {
    setTab(next); setFound(null); setHistory(null); setEditing(null); setDraft(''); setDeleteId(''); setError(''); setNotice('')
  }
  const dataKind = tab === 'diary' ? 'diary' : tab === 'interest' ? 'interest' : 'memory'
  const all = found ?? entries(state[tab === 'interest' ? 'interests' : tab === 'diary' ? 'diary' : 'memories']).filter(row => text(row.scope) === scope)
  const displayed = all.filter(row => (showForgotten || (!row.forgotten && !row.suppressed)) && (showHistory || !row.superseded))
  function listAction() { return tab === 'memory' ? 'memory.search' : dataKind + '.list' }
  function searchData() { return { scope, query, includeForgotten: showForgotten, includeHistory: showHistory } }
  function search() {
    void act(listAction(), searchData(), result => { setFound(entries(result.items)) })
  }
  function save() {
    const data: Entry = { scope, text: draft }
    if (editing) { data.id = editing.id; data.expectedRevision = editing.revision ?? 1 }
    if (tab === 'memory') { data.kind = kind; data.factKey = factKey }
    void act(tab === 'diary' ? 'diary.correct' : dataKind + '.upsert', data, () => { setEditing(null); setDraft(''); setFactKey(''); setFound(null) }, true)
  }
  async function saveBackupFile() {
    if (!saveFile || !backup || lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await saveFile('小克加密备份.json', backup); setNotice('已保存加密文件') }
    catch (cause) { setError(cause instanceof Error ? cause.message : '文件未保存，加密内容仍在编辑区') }
    finally { lock.current = false; setBusy(false) }
  }

  return <section className="life-panel" aria-label="小克的生活记录">
    <header><h2>一起积累的生活</h2><p>查看经历、纠正记忆，也给陪伴留出独立额度。</p></header>
    {!available && <p role="status">生活模块尚未连接；此处不会模拟保存或执行。</p>}
    <nav aria-label="生活分类">{([['memory', '记忆'], ['diary', '日记'], ['interest', '兴趣'], ['budget', '额度'], ['attention', '注意力'], ['backup', '备份']] as const).map(([key, label]) =>
      <button key={key} aria-pressed={tab === key} onClick={() => select(key)} disabled={busy}>{label}</button>)}</nav>
    {error && <p role="alert" className="life-error">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    <fieldset disabled={!available || busy}>
      {['memory', 'diary', 'interest'].includes(tab) && <>
        <form className="life-search" onSubmit={event => { event.preventDefault(); search() }}>
          <label>会话范围{scopes ? <select aria-label="会话范围" value={scope} onChange={event => { setScope(event.target.value); setFound(null); setHistory(null); setEditing(null); setDraft('') }}>{scopes.map(item=><option key={item.id} value={item.id}>{item.label}</option>)}</select> : <input aria-label="会话范围" value={scope} onChange={event => { setScope(event.target.value); setFound(null); setHistory(null); setEditing(null); setDraft('') }} />}</label>
          <label>搜索内容<input aria-label="搜索内容" value={query} onChange={event => setQuery(event.target.value)} /></label>
          <label className="life-check"><input type="checkbox" checked={showForgotten} onChange={event => setShowForgotten(event.target.checked)} />包含忘掉的记录</label>
          <label className="life-check"><input type="checkbox" checked={showHistory} onChange={event => setShowHistory(event.target.checked)} />包含已变化的事实</label>
          <button type="submit">搜索</button>
        </form>
        {tab === 'diary' && <p className="life-note">日记来自已保存的真实经历。没有证据的经历不会补写。</p>}
        {(tab !== 'diary' || editing) && <form className="life-editor" onSubmit={event => { event.preventDefault(); save() }}>
          <label>{editing ? '纠正内容' : tab === 'interest' ? '记录兴趣' : '记下一件有意义的事'}<textarea aria-label="记录内容" required maxLength={8000} value={draft} onChange={event => setDraft(event.target.value)} /></label>
          {tab === 'memory' && <div className="life-row"><label>类别<select value={kind} onChange={event => setKind(event.target.value)}>
            <option value="fact">事实</option><option value="inference">推测</option><option value="preference">偏好</option><option value="event">经历</option><option value="relationship">关系</option><option value="task">任务</option>
          </select></label><label>同一事实的标记（选填）<input value={factKey} maxLength={160} onChange={event => setFactKey(event.target.value)} placeholder="如：喜欢的饮料" /></label></div>}
          <div className="life-row"><button type="submit" disabled={!draft.trim()}>{editing ? '保存纠正' : '保存记录'}</button>{editing && <button type="button" onClick={() => { setEditing(null); setDraft('') }}>取消编辑</button>}</div>
        </form>}
        <div className="life-records">{displayed.length === 0 && <p className="life-empty">还没有符合条件的记录。</p>}{displayed.map(row => <article key={text(row.id)}>
          <p className="life-record-text">{text(row.text)}</p>
          <small>{date(row.occurredAt ?? row.createdAt)} · {row.forgotten || row.suppressed ? '已忘掉，不主动提起' : row.superseded ? '已有新事实' : text(row.kind) === 'inference' ? '有来源的推测' : text(row.status) === 'unverified' ? '自动记录，仍可纠正' : '已记录'}</small>
          {Boolean(row.evidenceMissing) && <p className="life-note">部分来源已不可用；这里仅保留当时的记录，不补造缺失经历。</p>}
          {entries(row.sourceRefs).length > 0 && <details><summary>查看来源</summary>{entries(row.sourceRefs).map(ref => <p key={text(ref.kind) + ':' + text(ref.id)}>{text(ref.kind)} · {text(ref.scope)} · {text(ref.id)}</p>)}</details>}
          <div className="life-row life-actions">
            <button onClick={() => { setEditing(row); setDraft(text(row.text)); setFactKey(text(row.factKey)); setKind(text(row.kind) || 'fact'); setScope(text(row.scope) || scope) }}>纠正</button>
            {tab === 'memory' && <><button onClick={() => void act('memory.forget', { id: row.id, scope: row.scope, forgotten: !row.forgotten }, () => setFound(null), true)}>{row.forgotten ? '恢复主动记忆' : '忘掉'}</button>
              <button onClick={() => void act('memory.history', { id: row.id, scope: row.scope }, result => { setHistory(entries(result.items)) })}>变化记录</button></>}
            <button className="life-danger" onClick={() => setDeleteId(text(row.id))}>删除</button>
          </div>
          {deleteId === row.id && <div role="group" aria-label="确认删除" className="life-delete">
            <p>{tab === 'memory' ? '这会清理该事实的来源消息和所有派生记录，包括可能保留它的旧回复。' : '这会清理此记录及其派生内容，包括可能保留它的旧回复。'}此设备保留删除记录，恢复旧备份时会继续排除它。</p>
            <button className="life-danger" onClick={() => void act(dataKind + '.delete', { id: row.id, scope: row.scope }, () => { setDeleteId(''); setFound(null); setHistory(null) }, true)}>确认彻底删除</button>
            <button onClick={() => setDeleteId('')}>保留</button>
          </div>}
        </article>)}</div>
        {history && <aside className="life-history"><h3>事实的变化</h3>{history.map((row, index) => <p key={index}>{date(row.occurredAt ?? row.updatedAt)}：{text(row.text)}</p>)}<button onClick={() => setHistory(null)}>收起</button></aside>}
      </>}
      {tab === 'budget' && <>
        <p>主人陪聊和自主活动分别记账。此处为当前账本累计额度，不会按天自动清零。</p>
        <div className="life-budget-cards">{[['owner', '主人陪聊'], ['activity', '自主活动']].map(([key, label]) => { const value = object(budgets[key]); return <article key={key}><h3>{label}</h3><strong>{number(value.remaining)}</strong><p>剩余 / 总额 {number(value.limit)}</p><small>已用 {number(value.spent)} · 在途保留 {number(value.held)} · 费用未知 {number(value.unknownRequests)} 笔</small></article> })}</div>
        {Boolean(budgets.stopActivity) && <p role="status">自主收费活动已停止；主人陪聊使用独立额度。</p>}
        <p className="life-note">次数上限不等于金额上限。未知价格时保留授权上界；取消请求不保证服务端退款。</p>
        <form onSubmit={event => { event.preventDefault(); void act('budget.configure', { unit, currency: unit === 'calls' ? '' : currency, ownerLimit: Number(ownerLimit), activityLimit: Number(activityLimit), warningRemaining: Number(warning), allowUnknownPrice: unknown, unknownPriceCeiling: unknown ? Number(ceiling) : 0 }) }}>
          <label>预算单位<select value={unit} onChange={event => setUnit(event.target.value)}><option value="calls">调用次数</option><option value="micros">金额微单位（一元为 1000000）</option></select></label>
          {unit === 'micros' && <label>币种<input value={currency} pattern="[A-Z]{3}" onChange={event => setCurrency(event.target.value.toUpperCase())} /></label>}
          <div className="life-row"><label>主人额度<input aria-label="主人额度" type="number" min="0" max="1000000000000" step="1" required value={ownerLimit} onChange={event => setOwnerLimit(event.target.value)} /></label><label>活动额度<input aria-label="活动额度" type="number" min="0" max="1000000000000" step="1" required value={activityLimit} onChange={event => setActivityLimit(event.target.value)} /></label></div>
          <label>活动剩余多少时提示并停止<input type="number" min="0" max="1000000000000" step="1" required value={warning} onChange={event => setWarning(event.target.value)} /></label>
          <label className="life-check"><input type="checkbox" checked={unknown} onChange={event => setUnknown(event.target.checked)} />我允许未知价格调用使用下面的保守上限，理解这不保证实际金额</label>
          {unknown && <label>未知价格单次上限<input type="number" min="1" max={unit === 'calls' ? '1' : '1000000000000'} step="1" required value={ceiling} onChange={event => setCeiling(event.target.value)} /></label>}
          <button type="submit">保存额度</button>
        </form>
      </>}
      {tab === 'attention' && <>
        <p>当前：{text(attention.mode) === 'busy' ? '这段时间普通事不打扰' : text(attention.mode) === 'quiet' ? '安静陪伴，普通事暂存' : '可以自然交流'}</p>
        {number(attention.until) > 0 && <p>约定结束时间：{date(attention.until)}</p>}
        <label>暂时去玩多久（分钟）<input type="number" min="1" max="10080" value={minutes} onChange={event => setMinutes(event.target.value)} /></label>
        <div className="life-row"><button onClick={() => void act('attention.set', { mode: 'busy', until: Date.now() + Number(minutes) * 60000, maxBackgroundTasks: 2 })}>这段时间普通事不打扰</button><button onClick={() => void act('attention.set', { mode: 'available', maxBackgroundTasks: 2 })}>我回来啦</button><button onClick={() => void act('attention.set', { mode: 'quiet', maxBackgroundTasks: 2 })}>安静陪伴</button></div>
        <p className="life-note">这里只更改明确的陪伴约定；关闭浮球或停止操作不会一并关闭陪聊。</p>
        <p>暂存事件：{entries(attention.pendingEvents).length} 条</p>
      </>}
      {tab === 'backup' && <>
        <p>备份先在手机端加密。保存文件后自行保管口令；密钥和真实资料不进入源码仓库。旧备份文件本身不会因本机删除而改变；换设备时应使用带最新删除记录的备份。</p>
        <label>备份口令<input aria-label="备份口令" type="password" minLength={8} maxLength={256} value={password} onChange={event => setPassword(event.target.value)} autoComplete="off" /></label>
        <button disabled={password.length < 8} onClick={() => void act('backup.export', { password }, result => { setBackup(text(result.text)); setPassword('') })}>生成加密备份</button>
        <button type="button" onClick={()=>importFile.current?.click()}>选择已有加密备份</button>
        <input ref={importFile} type="file" accept=".json,application/json" hidden onChange={event=>{
          const file=event.target.files?.[0];event.target.value='';if(!file)return
          if(file.size>16*1024*1024){setError('备份超过 16 MB 限制');return}
          void file.text().then(value=>{setBackup(value);setNotice('已读取加密备份，尚未恢复');setError('')}).catch(()=>setError('文件读取失败，原数据未更改'))
        }}/>
        <label>加密备份内容<textarea aria-label="加密备份内容" value={backup} onChange={event => setBackup(event.target.value)} rows={7} /></label>
        <div className="life-row"><button disabled={!backup || !saveFile} onClick={() => void saveBackupFile()}>保存加密文件</button><button disabled={!backup || password.length < 8} onClick={() => setRestoreConfirm(true)}>恢复备份</button></div>
        {restoreConfirm && <div className="life-delete"><p>恢复会合并较新的资料、保留删除屏蔽与已用额度，并暂停联网。请先停止在途任务。</p><button onClick={() => void act('backup.restore', { text: backup, password }, () => { setRestoreConfirm(false); setPassword(''); setBackup('') })}>确认合并恢复</button><button onClick={() => setRestoreConfirm(false)}>取消</button></div>}
      </>}
    </fieldset>
  </section>
}
