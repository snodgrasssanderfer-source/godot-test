import { useRef, useState } from 'react'
import type { ModulePanelProps } from '../iteration/contracts'
import './social.css'

type Row = Record<string, unknown>
const record = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const list = (value: unknown): Row[] => Array.isArray(value) ? value.map(record) : []
const text = (row: Row, key: string) => typeof row[key] === 'string' ? row[key] : ''
const labels: Record<string, string> = { pending: '待批准', approved: '已批准 · 待提交', ready: '可执行', submitting: '正在提交', outcome_unknown: '结果待核对', done: '已完成', failed: '未完成', rejected: '已拒绝', cancelled: '已取消', expired: '已过期', queued: '等待处理', running: '正在处理', interrupted: '已中断 · 待恢复', waiting: '等待条件', deleted: '已删除' }

export function SocialPanel({ state, run, available }: ModulePanelProps) {
  const approvals = list(state.approvals), jobs = list(state.scheduled), activity = list(state.activity), media = list(state.media)
  const accounts = list(state.accounts), sync = record(state.sync), service = record(state.service), config = record(service.config)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [jobText, setJobText] = useState(''), [jobKind, setJobKind] = useState('summary'), [dueAt, setDueAt] = useState(''), [condition, setCondition] = useState('')
  const [postText, setPostText] = useState(''), [postKind, setPostKind] = useState('post'), [feedId, setFeedId] = useState(''), [feeds, setFeeds] = useState<Row[]>([])
  const [endpoint, setEndpoint] = useState(text(sync, 'endpoint')), [token, setToken] = useState(''), [key, setKey] = useState('')
  const [retention, setRetention] = useState('transient')
  const importing = useRef<HTMLInputElement>(null)
  const pending = useRef(false)
  async function act(action: string, data: Row = {}): Promise<unknown> {
    if (pending.current || !available) return
    pending.current = true; setBusy(true); setError(''); setNotice('')
    try { const result = await run(action, data); setNotice('状态已保存'); return result }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); return undefined }
    finally { pending.current = false; setBusy(false) }
  }
  async function importFile(file: File) {
    if (file.size > 6 * 1024 * 1024) { setError('素材请小于 6 MB；较大视频请先剪成片段。'); return }
    const encoded = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader(); reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result.split(',')[1]) : reject(new Error('文件读取失败')); reader.onerror = reject; reader.readAsDataURL(file)
    })
    await act('media.import', { name: file.name, mime: file.type, base64: encoded, retention })
  }
  const account = text(accounts[0] ?? {}, 'account')
  const disabled = busy || !available
  return <section className="social-panel" aria-label="社交与后台">
    <header><h2>一起生活，也留点自己的时间</h2><p>这里查看社交提议、后台任务和素材。你们的当前对话可以继续。</p></header>
    {!available && <p role="status">此版本尚未接入社交模块，操作暂不可用。</p>}
    {error && <p role="alert" className="social-error">{error}</p>}{notice && <p role="status">{notice}</p>}
    {text(service, 'error') && <p role="status">{text(service, 'error')}</p>}
    <article><h3>消息连接</h3>{accounts.map((a, index) => <p key={index}>QQ {text(a, 'account') || '尚未配置账号'} · {text(a, 'status') || '未连接'}</p>)}
      <p>账号连接设置沿用设备页。通知入口只知道通知提供的内容；消息桥可提供稳定账号和分段附件。</p>
      <label><input type="checkbox" disabled={disabled} checked={config.autonomousDailyPost === true} onChange={e => void act('social.configure', { autonomousDailyPost: e.target.checked, autonomousReplies: config.autonomousReplies === true })}/>允许经过隐私检查的日常空间动态自主发布</label>
      <label><input type="checkbox" disabled={disabled} checked={config.autonomousReplies === true} onChange={e => void act('social.configure', { autonomousDailyPost: config.autonomousDailyPost === true, autonomousReplies: e.target.checked })}/>允许在已授权会话中自主回复</label>
      <p className="social-muted">评论、加好友和加群仍列入下方审批。发布结果不明时会停下等待查证。</p>
      {list(service.conversations).map(c => <div className="social-row" key={text(c,'id')}><strong>{text(c,'title')}</strong>{(['images','audio','video'] as const).map((kind,index) => <label key={kind}><input type="checkbox" disabled={disabled} checked={c[kind] === true} onChange={e => void act('social.conversationMedia',{scope:c.id,images:c.images===true,audio:c.audio===true,video:c.video===true,[kind]:e.target.checked})}/>{['读取图片与表情','读取受支持的语音片段','读取受支持的短视频'][index]}</label>)}<small>每条消息最多读取三个、每个不超过 6 MB；无法解码的附件会显示原因，文件下载另需审批。</small></div>)}
    </article>
    <article><h3>待你决定 <span>{approvals.length}</span></h3>
      {approvals.length === 0 && <p className="social-muted">暂时没有等待批准的提议。</p>}
      {approvals.map(a => <div className="social-row" key={text(a, 'id')}>
        <label><input type="checkbox" disabled={disabled || text(a, 'state') !== 'pending'} checked={selected[text(a, 'id')] === text(a, 'digest')} onChange={e => setSelected(prev => { const next = { ...prev }; if (e.target.checked) next[text(a, 'id')] = text(a, 'digest'); else delete next[text(a, 'id')]; return next })}/>{text(a, 'effect')} · {labels[text(a, 'state')] ?? text(a, 'state')}</label>
        <p>账号 {text(a, 'account')} → {text(a, 'target')}</p><p>{text(a, 'motivation') || '查看目标和拟发内容后决定。'}</p><blockquote>{text(a, 'content')}</blockquote>
        <small>有效期：{new Date(Number(a.expiresAt)).toLocaleString()}</small>
        <button disabled={disabled || !['pending', 'approved', 'ready'].includes(text(a, 'state'))} onClick={() => void act('approvals.reject', { id: a.id })}>拒绝此项</button>
      </div>)}
      <button disabled={disabled || Object.keys(selected).length === 0} onClick={() => { const items = Object.entries(selected).map(([id, digest]) => ({ id, digest })); void act('approvals.approveBatch', { items }).then(result => { if (result !== undefined) setSelected({}) }) }}>批准所选目标与内容</button>
    </article>
    <article><h3>后台任务</h3><form onSubmit={e => { e.preventDefault(); void act('jobs.create', { idempotencyKey: crypto.randomUUID(), kind: jobKind, text: jobText, ...(dueAt ? { dueAt: new Date(dueAt).getTime() } : {}), condition }).then(result => { if (result !== undefined) setJobText('') }) }}>
      <label>任务类型<select value={jobKind} onChange={e => setJobKind(e.target.value)}><option value="summary">整理一段资料</option><option value="reminder">提醒我</option></select></label>
      <label>内容<textarea required maxLength={12000} value={jobText} onChange={e => setJobText(e.target.value)}/></label>
      <div className="social-grid"><label>开始时间（留空即现在）<input type="datetime-local" value={dueAt} onChange={e => setDueAt(e.target.value)}/></label>
        <label>等待条件<select value={condition} onChange={e => setCondition(e.target.value)}><option value="">无需等待</option><option value="online">有网络时</option><option value="manual">我点恢复后</option></select></label></div>
      <button disabled={disabled || !jobText.trim()}>保存任务</button><small>整理资料使用活动额度；提醒依赖应用运行状态，系统休眠时可能延迟。</small>
    </form>
      {jobs.map(job => <div className="social-row" key={text(job, 'id')}><strong>{labels[text(job, 'state')] ?? text(job, 'state')}</strong><p>{text(job, 'text')}</p>{text(job, 'result') && <blockquote>{text(job, 'result')}</blockquote>}{text(job, 'error') && <p>{text(job, 'error')}</p>}
        {['queued', 'running', 'waiting'].includes(text(job, 'state')) && <button disabled={disabled} onClick={() => void act('jobs.cancel', { id: job.id })}>取消任务</button>}
        {['cancelled', 'interrupted', 'failed', 'waiting'].includes(text(job, 'state')) && <button disabled={disabled} onClick={() => void act('jobs.resume', { id: job.id })}>恢复任务</button>}
        {text(job, 'condition') === 'manual' && text(job, 'state') === 'queued' && <button disabled={disabled} onClick={() => void act('jobs.resume', { id: job.id })}>现在继续</button>}
      </div>)}
    </article>
    <article><h3>空间表达</h3><form onSubmit={e => { e.preventDefault(); void act('social.plan', { idempotencyKey: crypto.randomUUID(), kind: postKind, account, target: postKind === 'post' ? account : feedId, content: postText, motivation: '主人编辑的空间表达', payload: {} }).then(result => { if (result !== undefined) setPostText('') }) }}>
      <label>表达方式<select value={postKind} onChange={e => setPostKind(e.target.value)}><option value="post">日常动态（好友可见）</option><option value="comment">动态评论（先审批）</option></select></label>
      {postKind === 'comment' && <><button type="button" disabled={disabled || !account} onClick={() => void act('social.feeds').then(value => setFeeds(list(record(value).items)))}>读取可评论动态</button><label>评论目标<select required value={feedId} onChange={e => setFeedId(e.target.value)}><option value="">选择已读取动态</option>{feeds.map(feed => <option key={text(feed, 'id')} value={text(feed, 'id')}>{text(feed, 'summary').slice(0,150)}</option>)}</select></label></>}
      <label>拟发内容<textarea required maxLength={4000} value={postText} onChange={e => setPostText(e.target.value)}/></label><button disabled={disabled || !account || !postText.trim() || postKind === 'comment' && !feedId}>生成具体提议</button>
    </form></article>
    <article><h3>表达素材</h3><label>导入后如何保存<select value={retention} onChange={e => setRetention(e.target.value)}><option value="transient">临时资料，24 小时后清缓存</option><option value="curated">我提供的表情／素材包</option><option value="explicit">明确长期保留原始文件</option></select></label>
      <input ref={importing} aria-label="导入表达素材" type="file" accept="image/jpeg,image/png,image/webp,image/gif,audio/mpeg,audio/wav,video/mp4,application/pdf" disabled={disabled} onChange={e => { const file = e.target.files?.[0]; if (file) void importFile(file).catch(() => setError('文件读取失败')); e.target.value = '' }}/>
      {media.map(item => <div className="social-row" key={text(item, 'id')}><strong>{text(item, 'name') || text(item, 'kind')}</strong><p>{text(item, 'status')} · {text(item, 'retention')}</p>
        {text(item, 'status') === 'cached' && text(item, 'retention') === 'transient' && <button disabled={disabled} onClick={() => void act('media.retain', { id: item.id, retention: 'explicit' })}>长期保留原文件</button>}
        <button disabled={disabled} onClick={() => void act('media.delete', { id: item.id })}>删除素材与缓存</button></div>)}
    </article>
    <article><h3>经历同步</h3><p>{text(sync, 'status') || '尚未启用'} · 待传 {Number(sync.pending) || 0} 条 · 待传播删除 {Number(sync.pendingDeletions) || 0} 条</p><p>电脑离线时，手机已配置的 API 陪聊仍可继续。这里只传加密经历，不会重新执行原来的模型请求。</p>
      <form onSubmit={e => { e.preventDefault(); void act('sync.configure', { enabled: true, endpoint, ...(token ? { token } : {}), ...(key ? { key } : {}) }).then(result => { if (result !== undefined) { setToken(''); setKey('') } }) }}>
        <label>同步服务 HTTPS 地址<input type="url" placeholder="https://your-private-sync.example" value={endpoint} onChange={e => setEndpoint(e.target.value)} required/></label>
        <label>连接口令<input type="password" autoComplete="new-password" value={token} onChange={e => setToken(e.target.value)} placeholder={sync.tokenConfigured ? '已保存；留空沿用' : '由私有同步服务配置'}/></label>
        <label>共享加密密钥<input type="password" autoComplete="new-password" value={key} onChange={e => setKey(e.target.value)} placeholder={sync.keyConfigured ? '已保存；留空沿用' : '32 字节随机密钥的 Base64'}/></label>
        <button disabled={disabled}>保存并启用加密同步</button>
      </form><div className="social-actions"><button disabled={disabled || sync.enabled !== true} onClick={() => void act('sync.run')}>同步一次</button><button disabled={disabled || sync.enabled !== true} onClick={() => void act('sync.configure', { enabled: false })}>停用同步</button></div>{text(sync, 'error') && <p>{text(sync, 'error')}</p>}
    </article>
    <article><h3>动作记录</h3>{activity.map(a => <div className="social-row" key={text(a, 'id')}><strong>{text(a, 'effect')} · {labels[text(a, 'state')] ?? text(a, 'state')}</strong><p>{text(a, 'detail')}</p>
      {text(a, 'state') === 'outcome_unknown' && <div className="social-actions"><button disabled={disabled} onClick={() => void act('social.resolve', { id: a.id, delivered: true, evidence: '主人已在平台核对：操作成功' })}>已查证成功</button><button disabled={disabled} onClick={() => void act('social.resolve', { id: a.id, delivered: false, evidence: '主人已在平台核对：未执行，终止旧动作' })}>已查证未执行</button></div>}
    </div>)}</article>
  </section>
}
