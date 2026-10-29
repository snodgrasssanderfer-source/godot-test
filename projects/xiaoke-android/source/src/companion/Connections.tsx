import { useState } from 'react'
import { nativeCompanion, type Snapshot } from './bridge'

type Run = (action:string,data?:object)=>Promise<unknown>
export function Connections({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const [apps,setApps]=useState<{package:string;name:string}[]>([]),[pkg,setPkg]=useState(''),[minutes,setMinutes]=useState(15),[password,setPassword]=useState('')
  const [namespace,setNamespace]=useState(snapshot.settings.qqAccountLabel??'')
  const execute=(action:string,data:object={})=>void run(action,data).catch(()=>undefined)
  return <>
    <div className="card"><h2>悬浮陪伴 · 看一次，聊一会</h2><p>游戏中打开小窗，点击「看一次当前画面」，再告诉小克想聊什么。发送消息时才将画面交给所选模型；胜负判断依赖当时画面。没有持续录屏或麦克风。</p>
      <div className="form-actions"><button disabled={!nativeCompanion} onClick={()=>void run('launchableApps').then(v=>setApps(v as typeof apps)).catch(()=>undefined)}>选择本次观察应用</button><span className="pill">{snapshot.overlaySession?.running?`进行中 · ${Math.ceil(snapshot.overlaySession.remainingSeconds/60)} 分钟`:'未开启'}</span></div>
      <div className="form-grid"><label>画面范围<select value={pkg} onChange={e=>setPkg(e.target.value)}><option value="">只聊天，不读取画面</option>{apps.map(a=><option key={a.package} value={a.package}>{a.name}</option>)}</select></label><label>本次最长时长<select value={minutes} onChange={e=>setMinutes(Number(e.target.value))}>{[5,15,30,60].map(n=><option key={n} value={n}>{n} 分钟</option>)}</select></label></div>
      {pkg&&<label>设备控制密码（已设置时需要）<input type="password" value={password} onChange={e=>setPassword(e.target.value)} autoComplete="current-password"/></label>}
      <div className="form-actions"><button className="primary" disabled={!nativeCompanion} onClick={()=>void run('startOverlay',{package:pkg,minutes,password}).then(()=>setPassword('')).catch(()=>undefined)}>开始本次悬浮会话</button><button disabled={!snapshot.overlaySession?.running} onClick={()=>execute('stopOverlay')}>停止</button></div><small>小球可拖动、收起；展开时占用部分触控区域。锁屏隐藏内容；会话到时或服务被系统停止后需重新开启。</small>
    </div>
    <div className="card"><h2>QQ 通知的账号范围</h2><p>安卓通知不一定包含账号数字 ID。请给当前 QQ 登录账号设置独立标记，切换账号时同步修改，避免混用历史。</p><label>本机账号标记<input value={namespace} onChange={e=>setNamespace(e.target.value)} maxLength={80} placeholder="例如：我的陪伴账号"/></label><button onClick={()=>execute('settings',{...snapshot.settings,qqAccountLabel:namespace})}>保存账号范围</button></div>
    <QqConnection snapshot={snapshot} run={run}/>
  </>
}

/** NapCat on the owner's server: address, token, the owner's QQ and the groups she may talk in. Her own QQ account is read from the bridge. */
export function QqConnection({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const s=snapshot.settings, bridge=snapshot.messageBridge
  const [url,setUrl]=useState(s.oneBotUrl??''),[token,setToken]=useState(''),[owner,setOwner]=useState(s.ownerQqId??''),[groups,setGroups]=useState((s.qqGroups??[]).join(' '))
  const [note,setNote]=useState(''),[frequency,setFrequency]=useState(String(s.groupReplyFrequency??0.5))
  async function save(){
    try{
      await run('configureQq',{url:url.trim(),token:token.trim(),ownerQqId:owner.trim(),groups:groups.split(/[\s,，、]+/).filter(Boolean),groupReplyFrequency:Number(frequency)})
      setToken(''); setNote('已保存，正在连接。')
    }catch(e){setNote(e instanceof Error?e.message:String(e))}
  }
  return <div className="card"><h2>QQ（NapCat）</h2><span className="pill">{bridge?.status??'未连接'}</span>
    <label>WebSocket 地址<input type="url" value={url} onChange={e=>setUrl(e.target.value)} placeholder="wss://服务器地址:端口" autoComplete="off" autoCapitalize="none" spellCheck={false}/></label>
    <label>Token<input type="password" value={token} onChange={e=>setToken(e.target.value)} autoComplete="new-password" placeholder={bridge?.tokenConfigured?'已保存，留空不变':'NapCat 里设置的访问令牌'}/></label>
    <label>你的 QQ 号<input value={owner} onChange={e=>setOwner(e.target.value)} inputMode="numeric" placeholder="她只认这个号是你"/></label>
    <label>允许她发言的群<textarea value={groups} onChange={e=>setGroups(e.target.value)} rows={2} placeholder="群号，用空格或逗号隔开"/></label>
    <label>她在群里说话<select value={frequency} onChange={e=>setFrequency(e.target.value)}><option value="0.3">少一点</option><option value="0.5">适中</option><option value="0.8">多一点</option></select></label>
    <small>只有这里列出的群和你的私聊会被接收，其他会话她都不看。{bridge?.account?`她登录的 QQ：${bridge.account}`:'她自己的 QQ 号会在第一次连上时自动读出。'}</small>
    {bridge?.untrustedCertificate&&<div className="notice"><p>第一次连接这台服务器。请在服务器上运行 <code>openssl x509 -noout -fingerprint -sha256 -in 证书文件</code>，确认指纹和下面一致后再信任：</p><code style={{wordBreak:'break-all'}}>{bridge.untrustedCertificate}</code><button onClick={()=>void run('trustBridgeCert',{fingerprint:bridge.untrustedCertificate}).catch(()=>undefined)}>一致，信任这台服务器</button></div>}
    <div className="form-actions"><button className="primary" disabled={!nativeCompanion||!url.trim()} onClick={()=>void save()}>保存并连接</button><button disabled={!nativeCompanion} onClick={()=>void run('disconnectBridge').catch(()=>undefined)}>断开</button></div>
    {note&&<p role="status">{note}</p>}
  </div>
}
