import { useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'

type Row = Record<string, unknown>
const text = (row: Row, key: string) => typeof row[key] === 'string' ? row[key] : ''
type Props = { state: Row; config: Row; disabled: boolean; act: (action: string, data?: Row) => Promise<unknown> }

/** QR login (B站 web flow): show the code, poll every two seconds; the session renews itself afterwards. */
function BiliQrLogin({ disabled, act }: { disabled: boolean; act: Props['act'] }) {
  const [url, setUrl] = useState(''), [image, setImage] = useState(''), [status, setStatus] = useState('')
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearInterval(timer.current), [])
  async function begin() {
    window.clearInterval(timer.current); setStatus('')
    const result = await act('bili.login.start') as Row | undefined
    const link = result ? text(result, 'url') : ''
    if (!link) return
    setUrl(link); setImage(await QRCode.toDataURL(link, { margin: 1, width: 240 }))
    let rounds = 0
    timer.current = window.setInterval(() => {
      if (++rounds > 90) { window.clearInterval(timer.current); setStatus('二维码已过期，请重新生成'); return }
      void act('bili.login.poll').then(value => {
        const state = value && typeof value === 'object' ? text(value as Row, 'state') : ''
        if (state === 'scanned') setStatus('已扫码，请在手机上确认登录')
        if (state === 'expired') { window.clearInterval(timer.current); setStatus('二维码已过期，请重新生成'); setImage('') }
        if (state === 'confirmed') { window.clearInterval(timer.current); setStatus('登录成功'); setImage(''); setUrl('') }
      })
    }, 2000)
  }
  return <div className="bili-login">
    <button type="button" disabled={disabled} onClick={() => void begin()}>扫码登录B站</button>
    {image && <><img src={image} alt="B站登录二维码" width={240} height={240}/>
      <small>用B站App扫一扫（另一台手机，或截图后在扫一扫里从相册选这张图）。</small>
      <a href={'bilibili://browser?url=' + encodeURIComponent(url)}>在本机B站App里打开确认</a></>}
    {status && <p role="status">{status}</p>}
  </div>
}

export function BilibiliPanel({ state, config, disabled, act }: Props) {
  const [videoId, setVideoId] = useState(''), [resource, setResource] = useState<Row>({})
  const [kind, setKind] = useState('like'), [content, setContent] = useState(''), [motivation, setMotivation] = useState(''), [maxMiB, setMaxMiB] = useState(16)
  return <article><h3>B站活动</h3>
    <p>{text(state, 'status') || '尚未配置账号'}{text(state, 'account') && ` · ${text(state, 'name')}（${text(state, 'account')}）`}</p>
    <BiliQrLogin disabled={disabled} act={act}/>
    <small>登录后她用这个账号搜视频、读评论和回复通知；登录会自动续期。评论、回复都先进待审清单。</small>
    <div className="life-row">
      <button type="button" disabled={disabled || state.enabled !== true} onClick={() => void act('bili.configure', { enabled: false })}>停用B站活动</button>
      <button type="button" disabled={disabled || state.credentialConfigured !== true} onClick={() => void act('bili.configure', { enabled: false, clearCredential: true })}>退出登录</button>
    </div>
    <label><input type="checkbox" disabled={disabled} checked={config.autonomousBiliLikes === true} onChange={e => void act('social.configure', { autonomousBiliLikes: e.target.checked })}/>允许她自主点赞（B站视频、QQ 空间好友动态）</label>
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
