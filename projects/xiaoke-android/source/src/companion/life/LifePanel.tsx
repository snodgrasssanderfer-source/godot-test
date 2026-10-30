import { useEffect, useRef, useState } from 'react'
import type { ModulePanelProps } from '../iteration/contracts'
import './life.css'

type Entry = Record<string, unknown>
interface LifePanelProps extends ModulePanelProps { scopes?: {id:string;label:string}[]; saveFile?: (name: string, encryptedText: string) => Promise<unknown> }
const object = (value: unknown): Entry => value && typeof value === 'object' && !Array.isArray(value) ? value as Entry : {}
const entries = (value: unknown): Entry[] => Array.isArray(value) ? value.map(object) : []
const text = (value: unknown) => typeof value === 'string' ? value : ''
const number = (value: unknown) => typeof value === 'number' ? value : 0
const date = (value: unknown) => number(value) ? new Date(number(value)).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
const day = (value: unknown) => new Date(number(value)).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
/** "从 10/20 起" / "10/20 到 10/27（已过去）": facts that changed stay, marked as the past. */
const validity = (row: Entry) => {
  const until = number(row.validUntil)
  const from = number(row.validFrom) ? '从 ' + day(row.validFrom) : ''
  if (until > 0 && until <= Date.now()) return (from ? from + ' ' : '') + '到 ' + day(until) + '（已过去）'
  return from + ' 起' + (until > 0 ? '，到 ' + day(until) + ' 为止' : '') + (text(row.kind) === 'opinion' ? ' · 她的看法' : '')
}

/** Real host commands only. Root imports this optional panel and reloads module state after changes. */
export function LifePanel({ state, run, available, saveFile, scopes }: LifePanelProps) {
  const [tab, setTab] = useState<'memory' | 'profile' | 'diary' | 'interest' | 'budget' | 'attention' | 'backup'>('memory')
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
  const [inputPrice, setInputPrice] = useState(''), [cachedPrice, setCachedPrice] = useState(''), [outputPrice, setOutputPrice] = useState('')
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
  const dataKind = tab === 'diary' ? 'diary' : tab === 'interest' ? 'interest' : tab === 'profile' ? 'profile' : 'memory'
  const all = found ?? entries(state[tab === 'interest' ? 'interests' : tab === 'diary' ? 'diary' : tab === 'profile' ? 'profiles' : 'memories']).filter(row => text(row.scope) === scope)
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
    {!available && <p role="status" className="life-note">生活模块还没连上。</p>}
    <nav className="life-tabs" aria-label="生活分类">{([['memory', '记忆'], ['profile', '档案'], ['diary', '日记'], ['interest', '兴趣'], ['budget', '额度'], ['attention', '注意力'], ['backup', '备份']] as const).map(([key, label]) =>
      <button key={key} aria-pressed={tab === key} onClick={() => select(key)} disabled={busy}>{label}</button>)}</nav>
    {error && <p role="alert" className="life-error">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    <fieldset disabled={!available || busy}>
      {['memory', 'profile', 'diary', 'interest'].includes(tab) && <>
        <form className="life-search" onSubmit={event => { event.preventDefault(); search() }}>
          <label className="life-scope">会话范围{scopes ? <select aria-label="会话范围" value={scope} onChange={event => { setScope(event.target.value); setFound(null); setHistory(null); setEditing(null); setDraft('') }}>{scopes.map(item=><option key={item.id} value={item.id}>{item.label}</option>)}</select> : <input aria-label="会话范围" value={scope} onChange={event => { setScope(event.target.value); setFound(null); setHistory(null); setEditing(null); setDraft('') }} />}</label>
          <div className="life-searchbar"><input aria-label="搜索内容" placeholder="搜一搜…" value={query} onChange={event => setQuery(event.target.value)} /><button type="submit" className="xk-btn primary">搜索</button></div>
          <div className="life-filters"><label className="life-check"><input type="checkbox" checked={showForgotten} onChange={event => setShowForgotten(event.target.checked)} />含忘掉的</label>
          <label className="life-check"><input type="checkbox" checked={showHistory} onChange={event => setShowHistory(event.target.checked)} />含过去的</label></div>
        </form>
        {tab === 'diary' && <p className="life-note">她每晚根据当天真实发生的事写一篇。</p>}
        {tab === 'profile' && <p className="life-note">她对你、群友、游戏和作品的印象，聊着聊着会更新。</p>}
        {tab !== 'profile' && (tab !== 'diary' || editing) && <form className="life-editor" onSubmit={event => { event.preventDefault(); save() }}>
          <label>{editing ? '改成' : tab === 'interest' ? '她可能会喜欢的' : '让她记住一件事'}<textarea aria-label="记录内容" required maxLength={8000} value={draft} onChange={event => setDraft(event.target.value)} /></label>
          {tab === 'memory' && <div className="life-row"><label>类别<select value={kind} onChange={event => setKind(event.target.value)}>
            <option value="fact">事实</option><option value="inference">推测</option><option value="preference">偏好</option><option value="event">经历</option><option value="relationship">关系</option><option value="task">任务</option><option value="opinion">她的看法</option>
          </select></label><label>标签（选填）<input value={factKey} maxLength={160} onChange={event => setFactKey(event.target.value)} placeholder="如：喜欢的饮料" /></label></div>}
          <div className="life-row"><button type="submit" disabled={!draft.trim()}>{editing ? '保存纠正' : '保存记录'}</button>{editing && <button type="button" onClick={() => { setEditing(null); setDraft('') }}>取消编辑</button>}</div>
        </form>}
        <div className="life-records">{displayed.length === 0 && <p className="life-empty">这里还空着。</p>}{displayed.map(row => <article key={text(row.id)}>
          {tab === 'profile' && <h3>{text(row.name)}</h3>}
          <p className="life-record-text">{text(tab === 'profile' ? row.summary : row.text)}</p>
          {tab === 'memory' && (number(row.validFrom) > 0 || number(row.validUntil) > 0) && <small>{validity(row)}</small>}
          {tab !== 'profile' && <small>{date(row.occurredAt ?? row.createdAt)} · {row.forgotten || row.suppressed ? '已忘掉' : row.superseded ? '已经变了' : text(row.kind) === 'inference' ? '她猜的' : text(row.status) === 'unverified' ? '她自己记的' : '记住了'}</small>}
          {Boolean(row.evidenceMissing) && <p className="life-note">部分来源已经不在了。</p>}
          {entries(row.sourceRefs).length > 0 && <details><summary>来源</summary>{entries(row.sourceRefs).map(ref => <p key={text(ref.kind) + ':' + text(ref.id)}>{text(ref.kind)} · {text(ref.scope)} · {text(ref.id)}</p>)}</details>}
          <div className="life-row life-actions">
            {tab !== 'profile' && <button onClick={() => { setEditing(row); setDraft(text(row.text)); setFactKey(text(row.factKey)); setKind(text(row.kind) || 'fact'); setScope(text(row.scope) || scope) }}>纠正</button>}
            {tab === 'memory' && <><button onClick={() => void act('memory.forget', { id: row.id, scope: row.scope, forgotten: !row.forgotten }, () => setFound(null), true)}>{row.forgotten ? '想起来' : '忘掉'}</button>
              <button onClick={() => void act('memory.history', { id: row.id, scope: row.scope }, result => { setHistory(entries(result.items)) })}>变化记录</button></>}
            <button className="life-danger" onClick={() => setDeleteId(text(row.id))}>删除</button>
          </div>
          {deleteId === row.id && <div role="group" aria-label="确认删除" className="life-delete">
            <p>{tab === 'memory' ? '会连同来源消息和所有派生记录一起删掉。' : '会连同派生内容一起删掉。'}恢复旧备份也不会回来。</p>
            <button className="life-danger" onClick={() => void act(dataKind + '.delete', { id: row.id, scope: row.scope }, () => { setDeleteId(''); setFound(null); setHistory(null) }, true)}>确认彻底删除</button>
            <button onClick={() => setDeleteId('')}>保留</button>
          </div>}
        </article>)}</div>
        {history && <aside className="life-history"><h3>变化</h3>{history.map((row, index) => <p key={index}>{date(row.occurredAt ?? row.updatedAt)}：{text(row.text)}</p>)}<button onClick={() => setHistory(null)}>收起</button></aside>}
      </>}
      {tab === 'budget' && <>
        <div className="life-budget-cards">{[['owner', '陪你聊天'], ['activity', '她自己活动']].map(([key, label]) => { const value = object(budgets[key]); return <article key={key}><h3>{label}</h3><strong>{number(value.remaining)}</strong><p>剩余 / 共 {number(value.limit)}</p><span className="xk-meter"><span style={{ width: `${number(value.limit) ? Math.min(100, (number(value.spent) + number(value.held)) / number(value.limit) * 100) : 0}%` }} /></span><small>已用 {number(value.spent)}{number(value.held) ? ` · 进行中 ${number(value.held)}` : ''}</small>
          {number(object(value.tokens).prompt) > 0 && <small>缓存命中 {number(object(value.tokens).cacheRate)}%</small>}</article> })}</div>
        {Boolean(budgets.stopActivity) && <p role="status">她自己的额度用完了，陪你聊天不受影响。</p>}
        <form onSubmit={event => { event.preventDefault(); void act('budget.configure', { unit, currency: unit === 'calls' ? '' : currency, ownerLimit: Number(ownerLimit), activityLimit: Number(activityLimit), warningRemaining: Number(warning), allowUnknownPrice: unknown, unknownPriceCeiling: unknown ? Number(ceiling) : 0,
            ...(unit === 'micros' ? { inputPrice: Number(inputPrice || 0), cachedInputPrice: Number(cachedPrice || 0), outputPrice: Number(outputPrice || 0) } : {}) }) }}>
          <label>预算单位<select value={unit} onChange={event => setUnit(event.target.value)}><option value="calls">调用次数</option><option value="micros">金额（微单位，一元 = 1000000）</option></select></label>
          {unit === 'micros' && <label>币种<input value={currency} pattern="[A-Z]{3}" onChange={event => setCurrency(event.target.value.toUpperCase())} /></label>}
          <div className="life-row"><label>陪你聊天<input aria-label="主人额度" type="number" min="0" max="1000000000000" step="1" required value={ownerLimit} onChange={event => setOwnerLimit(event.target.value)} /></label><label>她自己活动<input aria-label="活动额度" type="number" min="0" max="1000000000000" step="1" required value={activityLimit} onChange={event => setActivityLimit(event.target.value)} /></label></div>
          {unit === 'micros' && <div className="life-row">
            <label>输入单价（每百万 tokens）<input aria-label="输入单价" type="number" min="0" step="1" value={inputPrice} onChange={event => setInputPrice(event.target.value)} /></label>
            <label>缓存输入单价<input aria-label="缓存输入单价" type="number" min="0" step="1" value={cachedPrice} onChange={event => setCachedPrice(event.target.value)} /></label>
            <label>输出单价<input aria-label="输出单价" type="number" min="0" step="1" value={outputPrice} onChange={event => setOutputPrice(event.target.value)} /></label>
          </div>}
          <label>她的额度剩多少时停下<input type="number" min="0" max="1000000000000" step="1" required value={warning} onChange={event => setWarning(event.target.value)} /></label>
          <label className="life-check"><input type="checkbox" checked={unknown} onChange={event => setUnknown(event.target.checked)} />不知道价格的调用也允许</label>
          {unknown && <label>这类调用单次最多<input type="number" min="1" max={unit === 'calls' ? '1' : '1000000000000'} step="1" required value={ceiling} onChange={event => setCeiling(event.target.value)} /></label>}
          <button type="submit">保存额度</button>
        </form>
      </>}
      {tab === 'attention' && <>
        <p>当前：{text(attention.mode) === 'busy' ? '先不打扰你' : text(attention.mode) === 'quiet' ? '安静陪着' : '随时可以聊'}</p>
        {number(attention.until) > 0 && <p className="life-note">到 {date(attention.until)}</p>}
        <label>多久（分钟）<input type="number" min="1" max="10080" value={minutes} onChange={event => setMinutes(event.target.value)} /></label>
        <div className="life-row"><button onClick={() => void act('attention.set', { mode: 'busy', until: Date.now() + Number(minutes) * 60000, maxBackgroundTasks: 2 })}>这段时间别打扰我</button><button onClick={() => void act('attention.set', { mode: 'available', maxBackgroundTasks: 2 })}>我回来啦</button><button onClick={() => void act('attention.set', { mode: 'quiet', maxBackgroundTasks: 2 })}>安静陪伴</button></div>
        {entries(attention.pendingEvents).length > 0 && <p className="life-note">攒着 {entries(attention.pendingEvents).length} 件事等你回来说</p>}
      </>}
      {tab === 'backup' && <>
        <p className="life-note">备份在手机上加密，口令自己记好。</p>
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
        {restoreConfirm && <div className="life-delete"><p>会和现在的资料合并，恢复后暂停联网。</p><button onClick={() => void act('backup.restore', { text: backup, password }, () => { setRestoreConfirm(false); setPassword(''); setBackup('') })}>确认合并恢复</button><button onClick={() => setRestoreConfirm(false)}>取消</button></div>}
      </>}
    </fieldset>
  </section>
}
