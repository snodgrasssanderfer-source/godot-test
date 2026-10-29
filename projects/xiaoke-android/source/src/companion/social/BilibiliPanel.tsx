import { useState } from 'react'

type Row = Record<string, unknown>
const text = (row: Row, key: string) => typeof row[key] === 'string' ? row[key] : ''
type Props = { state: Row; config: Row; disabled: boolean; act: (action: string, data?: Row) => Promise<unknown> }

export function BilibiliPanel({ state, config, disabled, act }: Props) {
  const [cookie, setCookie] = useState(''), [videoId, setVideoId] = useState(''), [resource, setResource] = useState<Row>({})
  const [kind, setKind] = useState('like'), [content, setContent] = useState(''), [motivation, setMotivation] = useState(''), [maxMiB, setMaxMiB] = useState(16)
  return <article><h3>B站活动</h3>
    <p>{text(state, 'status') || '尚未配置账号'}{text(state, 'account') && ` · ${text(state, 'name')}（${text(state, 'account')}）`}</p>
    <form onSubmit={e => { e.preventDefault(); void act('bili.configure', { enabled: true, ...(cookie ? { cookie } : {}) }).then(result => { if (result !== undefined) setCookie('') }) }}>
      <label>本机B站登录资料<input type="password" autoComplete="new-password" value={cookie} onChange={e => setCookie(e.target.value)} placeholder={state.credentialConfigured ? '已加密保存；留空重新核对' : '粘贴你提供的 Cookie，含 SESSDATA 和 bili_jct'}/></label>
      <small>登录资料仅用于你配置的账号。保存时读取账号身份，每次互动前再次核对。</small>
      <button disabled={disabled || !cookie.trim() && state.credentialConfigured !== true}>核对账号并启用</button>
      <button type="button" disabled={disabled || state.enabled !== true} onClick={() => void act('bili.configure', { enabled: false })}>停用B站活动</button>
      <button type="button" disabled={disabled || state.credentialConfigured !== true} onClick={() => void act('bili.configure', { enabled: false, clearCredential: true })}>清除本机登录资料</button>
    </form>
    <label><input type="checkbox" disabled={disabled} checked={config.autonomousBiliLikes === true} onChange={e => void act('social.configure', { autonomousBiliLikes: e.target.checked })}/>允许自主点赞已确认的视频</label>
    <label><input type="checkbox" disabled={disabled} checked={config.autonomousBiliFollowing === true} onChange={e => void act('social.configure', { autonomousBiliFollowing: e.target.checked })}/>允许自主关注已确认的UP主</label>
    <form onSubmit={e => { e.preventDefault(); setResource({}); void act('bili.inspect', { videoId }).then(result => { if (result && typeof result === 'object') setResource(result as Row) }) }}>
      <label>视频 BV 编号<input required pattern="BV[0-9A-Za-z]{10}" value={videoId} onChange={e => { setVideoId(e.target.value.trim()); setResource({}) }}/></label>
      <button disabled={disabled || !/^BV[0-9A-Za-z]{10}$/.test(videoId)}>读取并核对视频目标</button>
    </form>
    {text(resource, 'id') && <form onSubmit={e => { e.preventDefault(); void act('bili.plan', { kind, resourceId: resource.id, idempotencyKey: crypto.randomUUID(), motivation, ...(kind === 'comment' ? { content } : {}), ...(kind === 'download' ? { maxBytes: maxMiB * 1024 * 1024 } : {}) }).then(result => { if (result !== undefined) setContent('') }) }}>
      <p><strong>{text(resource, 'title')}</strong> · {text(resource, 'videoId')}</p><p>UP主：{text(resource, 'upName')}（{text(resource, 'upMid')}）</p><small>{text(resource, 'meaning')}</small>
      <label>互动方式<select value={kind} onChange={e => setKind(e.target.value)}><option value="like">点赞视频</option><option value="follow">关注此UP主</option><option value="comment">评论视频 · 先审批</option><option value="download">保存公开视频 · 先审批</option></select></label>
      {kind === 'comment' && <label>拟发评论<textarea required maxLength={4000} value={content} onChange={e => setContent(e.target.value)}/></label>}
      {kind === 'download' && <><label>最大下载体积（MB）<input type="number" min="1" max="16" required value={maxMiB} onChange={e => setMaxMiB(Number(e.target.value))}/></label><p>读取公开首P的单文件 MP4，成功后出现在加密素材中。平台未提供这种文件或超过上限时会停止。</p></>}
      <label>为什么想这样做<textarea required maxLength={1000} value={motivation} onChange={e => setMotivation(e.target.value)}/></label>
      <button disabled={disabled || state.connected !== true || !motivation.trim() || kind === 'comment' && !content.trim()}>生成此目标的具体提议</button>
    </form>}
  </article>
}
