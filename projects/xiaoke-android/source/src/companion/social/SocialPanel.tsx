import { useRef, useState } from 'react'
import type { ModulePanelProps } from '../iteration/contracts'
import './social.css'
import { BilibiliPanel } from './BilibiliPanel'
import { ArrowsClockwise, ClockCountdown, ClockCounterClockwise, Images, PencilSimpleLine, Planet, Television } from '@phosphor-icons/react'
import { Section } from '../ui'

type Row = Record<string, unknown>
const record = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const list = (value: unknown): Row[] => Array.isArray(value) ? value.map(record) : []
const text = (row: Row, key: string) => typeof row[key] === 'string' ? row[key] : ''
const labels: Record<string, string> = { pending: '待审', approved: '已放行', ready: '可执行', submitting: '正在提交', outcome_unknown: '结果待核对', done: '已完成', failed: '没成功', rejected: '已拒绝', cancelled: '已取消', expired: '已过期', queued: '等待处理', running: '正在处理', interrupted: '中断了', waiting: '等待条件', deleted: '已删除' }

const effects: Record<string, string> = { social_send: '发消息', public_post: '发说说', public_comment: '评论', like: '点赞', follow: '关注', download: '存视频', save: '存图', add_friend: '加好友', join_group: '加群', leave_group: '退群' }

function kindLabel(a: Row): string {
  const payload = record(a.payload)
  switch (text(a, 'kind')) {
    case 'bili_comment': return `B站评论 · 《${text(payload, 'title')}》`
    case 'save_media': return `存进资料库 · ${text(a, 'content')}`
    case 'comment': return 'QQ 空间评论'
    case 'post': return '发 QQ 空间说说'
    case 'reply': return `QQ 回复 · ${text(a, 'target')}`
    case 'add_friend': return `加好友请求 · ${text(a, 'target')}`
    case 'join_group': return `加群邀请 · ${text(a, 'target')}`
    case 'bili_reply': return 'B站回复'
    case 'qzone_reply': return '回复说说评论'
    case 'bili_download': return `存B站视频 · 《${text(payload, 'title')}》`
    default: return effects[text(a, 'effect')] ?? text(a, 'effect')
  }
}

export function SocialPanel({ state, run, available }: ModulePanelProps) {
  const approvals = list(state.approvals), jobs = list(state.scheduled), activity = list(state.activity), media = list(state.media)
  const accounts = list(state.accounts), sync = record(state.sync), service = record(state.service), config = record(service.config)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [previews, setPreviews] = useState<Record<string, string>>({})
  const [jobText, setJobText] = useState(''), [jobKind, setJobKind] = useState('summary'), [dueAt, setDueAt] = useState(''), [condition, setCondition] = useState('')
  const [postText, setPostText] = useState(''), [postKind, setPostKind] = useState('post'), [feedId, setFeedId] = useState(''), [feeds, setFeeds] = useState<Row[]>([])
  const [endpoint, setEndpoint] = useState(text(sync, 'endpoint')), [token, setToken] = useState(''), [key, setKey] = useState('')
  const [retention, setRetention] = useState('transient')
  const importing = useRef<HTMLInputElement>(null)
  const pending = useRef(false)
  async function act(action: string, data: Row = {}): Promise<unknown> {
    if (pending.current || !available) return
    pending.current = true; setBusy(true); setError(''); setNotice('')
    try { const result = await run(action, data); setNotice('好了'); return result }
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
  const pendingApprovals = approvals.filter(a => text(a, 'state') === 'pending')
  const bili = record(state.bilibili)
  return <section className="social-panel" aria-label="社交与后台">
    {!available && <p role="status" className="xk-hint">社交模块还没连上。</p>}
    {error && <p role="alert" className="social-error">{error}</p>}{notice && <p role="status" className="social-notice">{notice}</p>}
    {text(service, 'error') && <p role="status">{text(service, 'error')}</p>}
    <article className="social-approvals"><div className="xk-card-head"><h2>等你决定</h2>{pendingApprovals.length > 0 && <span className="xk-pill state-draft">{pendingApprovals.length}</span>}</div>
      {approvals.length === 0 && <p className="xk-hint">没有要你看的。她写的评论、说说、想存的图会先放这里。</p>}
      {approvals.map(a => { const id = text(a, 'id'), payload = record(a.payload), pending = text(a, 'state') === 'pending'; return <div className={'social-row' + (pending ? ' pending' : '')} key={id}>
        <div className="xk-card-head"><strong>{kindLabel(a)}</strong>{!pending && <span className="xk-pill">{labels[text(a, 'state')] ?? text(a, 'state')}</span>}</div>
        {text(a, 'motivation') && <p className="xk-hint">{text(a, 'motivation')}</p>}
        {text(a, 'kind') === 'save_media'
          ? <div className="xk-actions">{(Array.isArray(payload.mediaIds) ? payload.mediaIds as string[] : []).map(mediaId => previews[mediaId]
              ? <img key={mediaId} src={previews[mediaId]} alt={text(a, 'content')}/>
              : <button key={mediaId} className="xk-btn small" disabled={disabled} onClick={() => void act('media.preview', { id: mediaId }).then(r => { const v = record(r); if (text(v, 'base64')) setPreviews(prev => ({ ...prev, [mediaId]: `data:${text(v, 'mime')};base64,${text(v, 'base64')}` })) })}>看图</button>)}</div>
          : <blockquote>{text(a, 'content')}</blockquote>}
        {pending && <div className="xk-actions">
          <button className="xk-btn primary small" disabled={disabled} onClick={() => void act('approvals.approveBatch', { items: [{ id, digest: text(a, 'digest') }] })}>放行</button>
          <button className="xk-btn ghost small" disabled={disabled} onClick={() => void act('approvals.reject', { id })}>删掉</button>
        </div>}
      </div> })}
      {pendingApprovals.length > 1 && <button className="xk-btn" disabled={disabled} onClick={() => void act('approvals.approveBatch', { items: pendingApprovals.map(a => ({ id: text(a, 'id'), digest: text(a, 'digest') })) })}>全部放行</button>}
    </article>
    <Section icon={<Planet size={20} weight="duotone"/>} title="QQ 与空间" summary={accounts.filter(a => text(a, 'route') !== 'bilibili').map(a => text(a, 'status')).filter(Boolean)[0] || '说说、评论、自主回复'}>
      <label><input type="checkbox" disabled={disabled} checked={config.qzoneLife !== false} onChange={e => void act('social.configure', { qzoneLife: e.target.checked })}/>QQ 空间：偶尔写说说、回评论、看好友动态</label>
      <label><input type="checkbox" disabled={disabled} checked={config.autonomousReplies === true} onChange={e => void act('social.configure', { autonomousDailyPost: config.autonomousDailyPost === true, autonomousReplies: e.target.checked })}/>在允许的群里自己回复</label>
      <label><input type="checkbox" disabled={disabled} checked={config.autonomousDailyPost === true} onChange={e => void act('social.configure', { autonomousDailyPost: e.target.checked, autonomousReplies: config.autonomousReplies === true })}/>我在这里写的说说直接发</label>
      {text(record(service.qqActiveAdd), 'reason') && <p className="xk-hint">{text(record(service.qqActiveAdd), 'reason')}</p>}
      {list(service.conversations).map(c => <div className="social-row" key={text(c,'id')}><strong>{text(c,'title')}</strong>{(['images','audio','video'] as const).map((kind,index) => <label key={kind}><input type="checkbox" disabled={disabled} checked={c[kind] === true} onChange={e => void act('social.conversationMedia',{scope:c.id,images:c.images===true,audio:c.audio===true,video:c.video===true,[kind]:e.target.checked})}/>{['看图片和表情','听语音','看短视频'][index]}</label>)}</div>)}
    </Section>
    <Section icon={<PencilSimpleLine size={20} weight="duotone"/>} title="写说说" summary="你来写，或者评论好友动态">
      <form onSubmit={e => { e.preventDefault(); void act('social.plan', { idempotencyKey: crypto.randomUUID(), kind: postKind, account, target: postKind === 'post' ? account : feedId, content: postText, motivation: '主人编辑的空间表达', payload: {} }).then(result => { if (result !== undefined) setPostText('') }) }}>
        <label>发什么<select value={postKind} onChange={e => setPostKind(e.target.value)}><option value="post">说说</option><option value="comment">评论好友动态</option></select></label>
        {postKind === 'comment' && <><button type="button" className="xk-btn" disabled={disabled || !account} onClick={() => void act('social.feeds').then(value => setFeeds(list(record(value).items)))}>读取好友动态</button><label>评论哪条<select required value={feedId} onChange={e => setFeedId(e.target.value)}><option value="">选一条</option>{feeds.map(feed => <option key={text(feed, 'id')} value={text(feed, 'id')}>{text(feed, 'summary').slice(0,150)}</option>)}</select></label></>}
        <label>写点什么<textarea required maxLength={4000} value={postText} onChange={e => setPostText(e.target.value)}/></label><button className="xk-btn primary" disabled={disabled || !account || !postText.trim() || postKind === 'comment' && !feedId}>放进待审</button>
      </form>
    </Section>
    <Section icon={<Television size={20} weight="duotone"/>} title="B站" summary={text(bili, 'name') ? `${text(bili, 'name')} · ${text(bili, 'status')}` : text(bili, 'status') || '扫码登录后她能自己逛'}>
      <BilibiliPanel state={bili} config={config} disabled={disabled} act={act}/>
    </Section>
    <Section icon={<ClockCountdown size={20} weight="duotone"/>} title="交代给她的事" summary={jobs.length ? `${jobs.length} 件` : '整理资料、到点提醒'}>
      <form onSubmit={e => { e.preventDefault(); void act('jobs.create', { idempotencyKey: crypto.randomUUID(), kind: jobKind, text: jobText, ...(dueAt ? { dueAt: new Date(dueAt).getTime() } : {}), condition }).then(result => { if (result !== undefined) setJobText('') }) }}>
        <label>做什么<select value={jobKind} onChange={e => setJobKind(e.target.value)}><option value="summary">整理一段资料</option><option value="reminder">提醒我</option></select></label>
        <label>内容<textarea required maxLength={12000} value={jobText} onChange={e => setJobText(e.target.value)}/></label>
        <div className="xk-grid"><label>{jobKind === 'reminder' ? '什么时候提醒' : '什么时候（留空就现在）'}<input type="datetime-local" value={dueAt} onChange={e => setDueAt(e.target.value)}/></label>
          <label>等什么<select value={condition} onChange={e => setCondition(e.target.value)}><option value="">不用等</option><option value="online">有网时</option><option value="manual">我说继续</option></select></label></div>
        <button className="xk-btn primary" disabled={disabled || !jobText.trim() || jobKind === 'reminder' && !dueAt}>保存任务</button>
      </form>
      {jobs.map(job => <div className="social-row" key={text(job, 'id')}><div className="xk-card-head"><p>{text(job, 'text')}</p><span className="xk-pill">{labels[text(job, 'state')] ?? text(job, 'state')}</span></div>{text(job, 'result') && <blockquote>{text(job, 'result')}</blockquote>}{text(job, 'error') && <p className="xk-error-text">{text(job, 'error')}</p>}
        <div className="xk-actions">
        {['queued', 'running', 'waiting'].includes(text(job, 'state')) && <button className="xk-btn small ghost" disabled={disabled} onClick={() => void act('jobs.cancel', { id: job.id })}>取消任务</button>}
        {['cancelled', 'interrupted', 'failed', 'waiting'].includes(text(job, 'state')) && <button className="xk-btn small" disabled={disabled} onClick={() => void act('jobs.resume', { id: job.id })}>恢复任务</button>}
        {text(job, 'condition') === 'manual' && text(job, 'state') === 'queued' && <button className="xk-btn small primary" disabled={disabled} onClick={() => void act('jobs.resume', { id: job.id })}>现在继续</button>}
        </div>
      </div>)}
    </Section>
    <Section icon={<Images size={20} weight="duotone"/>} title="表情和素材" summary={media.length ? `${media.length} 个` : '给她用的表情包、图片'}>
      <label>怎么存<select value={retention} onChange={e => setRetention(e.target.value)}><option value="transient">临时用，24 小时后清掉</option><option value="curated">给她当表情包用（图片）</option><option value="explicit">一直留着原文件</option></select></label>
      <input ref={importing} aria-label="导入表达素材" type="file" accept="image/jpeg,image/png,image/webp,image/gif,audio/mpeg,audio/wav,video/mp4,application/pdf" disabled={disabled} onChange={e => { const file = e.target.files?.[0]; if (file) void importFile(file).catch(() => setError('文件读取失败')); e.target.value = '' }}/>
      {media.map(item => <div className="social-row" key={text(item, 'id')}><strong>{text(item, 'name') || text(item, 'kind')}</strong>
        <small>{text(item, 'retention') === 'curated' && text(item, 'kind') === 'image' ? (text(item, 'description') ? `她的表情包：${text(item, 'description')}` : '她的表情包 · 还没看过，联网时会看一眼') : `${text(item, 'status')} · ${text(item, 'retention')}`}</small>
        <div className="xk-actions">{text(item, 'status') === 'cached' && text(item, 'retention') === 'transient' && <button className="xk-btn small" disabled={disabled} onClick={() => void act('media.retain', { id: item.id, retention: 'explicit' })}>一直留着</button>}
        <button className="xk-btn small ghost danger" disabled={disabled} onClick={() => void act('media.delete', { id: item.id })}>删除</button></div></div>)}
    </Section>
    <Section icon={<ArrowsClockwise size={20} weight="duotone"/>} title="经历同步" summary={`${text(sync, 'status') || '没开'}${Number(sync.pending) ? ` · 待传 ${Number(sync.pending)}` : ''}`}>
      <p className="xk-hint">和你电脑上的她同步经历，全程加密。</p>
      <form onSubmit={e => { e.preventDefault(); void act('sync.configure', { enabled: true, endpoint, ...(token ? { token } : {}), ...(key ? { key } : {}) }).then(result => { if (result !== undefined) { setToken(''); setKey('') } }) }}>
        <label>同步地址<input type="url" placeholder="https://…" value={endpoint} onChange={e => setEndpoint(e.target.value)} required/></label>
        <label>口令<input type="password" autoComplete="new-password" value={token} onChange={e => setToken(e.target.value)} placeholder={sync.tokenConfigured ? '已保存，留空不变' : ''}/></label>
        <label>加密密钥<input type="password" autoComplete="new-password" value={key} onChange={e => setKey(e.target.value)} placeholder={sync.keyConfigured ? '已保存，留空不变' : '32 字节随机密钥的 Base64'}/></label>
        <button className="xk-btn primary" disabled={disabled}>保存并启用加密同步</button>
      </form><div className="xk-actions"><button className="xk-btn" disabled={disabled || sync.enabled !== true} onClick={() => void act('sync.run')}>同步一次</button><button className="xk-btn ghost" disabled={disabled || sync.enabled !== true} onClick={() => void act('sync.configure', { enabled: false })}>停用</button></div>{text(sync, 'error') && <p className="xk-error-text">{text(sync, 'error')}</p>}
    </Section>
    <Section icon={<ClockCounterClockwise size={20} weight="duotone"/>} title="她做过的事" summary={activity.length ? `最近 ${activity.length} 条` : '还没有'} defaultOpen={activity.some(a => text(a, 'state') === 'outcome_unknown')}>
      {activity.length === 0 && <p className="xk-hint">还没有。</p>}
      {activity.map(a => <div className="social-row" key={text(a, 'id')}><div className="xk-card-head"><strong>{effects[text(a, 'effect')] ?? text(a, 'effect')}</strong><span className="xk-pill">{labels[text(a, 'state')] ?? text(a, 'state')}</span></div>{text(a, 'detail') && <p className="xk-hint">{text(a, 'detail')}</p>}
        {text(a, 'state') === 'outcome_unknown' && <div className="xk-actions"><button className="xk-btn small" disabled={disabled} onClick={() => void act('social.resolve', { id: a.id, delivered: true, evidence: '主人已在平台核对：操作成功' })}>已查证成功</button><button className="xk-btn small" disabled={disabled} onClick={() => void act('social.resolve', { id: a.id, delivered: false, evidence: '主人已在平台核对：未执行，终止旧动作' })}>已查证未执行</button></div>}
      </div>)}
    </Section>
  </section>
}
