import { useState } from 'react'
import { nativeCompanion, type Snapshot } from './bridge'

type Run = (action:string,data?:object)=>Promise<unknown>
export function Connections({snapshot,run}:{snapshot:Snapshot;run:Run}) {
  const [apps,setApps]=useState<{package:string;name:string}[]>([]),[pkg,setPkg]=useState(''),[minutes,setMinutes]=useState(15),[password,setPassword]=useState('')
  const [url,setUrl]=useState(snapshot.settings.oneBotUrl??''),[account,setAccount]=useState(snapshot.settings.oneBotAccount??''),[token,setToken]=useState('')
  const [namespace,setNamespace]=useState(snapshot.settings.qqAccountLabel??''),[note,setNote]=useState('')
  const [target,setTarget]=useState(''),[targetType,setTargetType]=useState('private'),[title,setTitle]=useState('')
  const [owner,setOwner]=useState(snapshot.settings.ownerQqId),[share,setShare]=useState(snapshot.settings.shareOwnerMemoryWithQq??false),[mute,setMute]=useState(snapshot.settings.allowOwnerQqMute??false)
  const execute=(action:string,data:object={})=>void run(action,data).catch(()=>undefined)
  async function connect(){try{if(token){await run('saveBridgeToken',{token});setToken('')}await run('settings',{...snapshot.settings,oneBotEnabled:true,oneBotUrl:url,oneBotAccount:account});await run('connectBridge');setNote('正在连接，请查看账号核对结果。只有授权接收的会话才记录正文，默认不会自动回复。')}catch{/* shared error */}}
  return <>
    <div className="card"><h2>悬浮陪伴 · 看一次，聊一会</h2><p>游戏中打开小窗，点击「看一次当前画面」，再告诉小克想聊什么。发送消息时才将画面交给所选模型；胜负判断依赖当时画面。没有持续录屏或麦克风。</p>
      <div className="form-actions"><button disabled={!nativeCompanion} onClick={()=>void run('launchableApps').then(v=>setApps(v as typeof apps)).catch(()=>undefined)}>选择本次观察应用</button><span className="pill">{snapshot.overlaySession?.running?`进行中 · ${Math.ceil(snapshot.overlaySession.remainingSeconds/60)} 分钟`:'未开启'}</span></div>
      <div className="form-grid"><label>画面范围<select value={pkg} onChange={e=>setPkg(e.target.value)}><option value="">只聊天，不读取画面</option>{apps.map(a=><option key={a.package} value={a.package}>{a.name}</option>)}</select></label><label>本次最长时长<select value={minutes} onChange={e=>setMinutes(Number(e.target.value))}>{[5,15,30,60].map(n=><option key={n} value={n}>{n} 分钟</option>)}</select></label></div>
      {pkg&&<label>设备控制密码（已设置时需要）<input type="password" value={password} onChange={e=>setPassword(e.target.value)} autoComplete="current-password"/></label>}
      <div className="form-actions"><button className="primary" disabled={!nativeCompanion} onClick={()=>void run('startOverlay',{package:pkg,minutes,password}).then(()=>setPassword('')).catch(()=>undefined)}>开始本次悬浮会话</button><button disabled={!snapshot.overlaySession?.running} onClick={()=>execute('stopOverlay')}>停止</button></div><small>小球可拖动、收起；展开时占用部分触控区域。锁屏隐藏内容；会话到时或服务被系统停止后需重新开启。</small>
    </div>
    <div className="card"><h2>QQ 通知的账号范围</h2><p>安卓通知不一定包含账号数字 ID。请给当前 QQ 登录账号设置独立标记，切换账号时同步修改，避免混用历史。</p><label>本机账号标记<input value={namespace} onChange={e=>setNamespace(e.target.value)} maxLength={80} placeholder="例如：我的陪伴账号"/></label><button onClick={()=>execute('settings',{...snapshot.settings,qqAccountLabel:namespace})}>保存账号范围</button></div>
    <div className="card"><h2>NapCat · 独立 QQ 通道</h2><p>连接云端 NapCat，独立接收私聊与群聊，按数字 ID 绑定目标，不抢占手机 QQ 输入框。复用 AstrBot 的消息分段和发送路线；本 APK 不捆绑 QQ 登录客户端，安卓 UI 路线仍可单独使用。</p><span className="pill">{snapshot.messageBridge?.status??'未配置'}</span>
      <label>WebSocket 地址<input type="url" value={url} onChange={e=>setUrl(e.target.value)} placeholder="wss://你的消息桥地址" autoComplete="off"/></label><label>桥端 QQ 账号 ID<input value={account} onChange={e=>setAccount(e.target.value)} inputMode="numeric" placeholder="连接后须与桥返回的账号一致"/></label>
      <label>访问令牌<input type="password" value={token} onChange={e=>setToken(e.target.value)} autoComplete="new-password" placeholder={snapshot.messageBridge?.tokenConfigured?'已保存在本机，留空保持':'由你自己的桥提供；不需要 QQ 密码'}/></label><small>连接会访问你填写的地址并核对账号，需先在模型页启用联网。没有会话授权时不会自动发消息。</small>
      <div className="form-actions"><button disabled={!nativeCompanion||!url||!account||!snapshot.settings.networkEnabled} onClick={()=>void connect()}>保存并连接</button><button disabled={!nativeCompanion} onClick={()=>execute('disconnectBridge')}>断开</button><button disabled={!snapshot.messageBridge?.tokenConfigured} onClick={()=>execute('clearBridgeToken')}>删除令牌</button></div>
      {note&&<p role="status">{note}</p>}
      <details><summary>本人 QQ 私聊与本机记忆连续</summary><p>只对已核对桥端账号的本人私聊生效。群聊始终隔离，QQ 消息仍不能控制设备。</p><label>主人 QQ 数字 ID<input value={owner} onChange={e=>setOwner(e.target.value)} inputMode="numeric"/></label><label className="check-row"><input type="checkbox" checked={share} onChange={e=>setShare(e.target.checked)}/>允许此本人私聊读写主人长期记忆（模型新增项仍待确认）</label><label className="check-row"><input type="checkbox" checked={mute} onChange={e=>setMute(e.target.checked)}/>允许这个主人私聊的精确指令 #群静音 停止群回复</label><button onClick={()=>execute('settings',{...snapshot.settings,ownerQqId:owner,shareOwnerMemoryWithQq:share,allowOwnerQqMute:mute})}>保存本人绑定</button></details>
      <details><summary>主动绑定已有好友或群</summary><p>只建立本机路由记录；不加好友、不加入群、不发送消息。</p><label>目标类型<select value={targetType} onChange={e=>setTargetType(e.target.value)}><option value="private">好友私聊</option><option value="group">QQ群</option></select></label><label>目标数字 ID<input value={target} onChange={e=>setTarget(e.target.value)} inputMode="numeric"/></label><label>本机显示名称<input value={title} onChange={e=>setTitle(e.target.value)} maxLength={160}/></label><button disabled={!snapshot.messageBridge?.connected||!target} onClick={()=>execute('bindBridgeConversation',{targetId:target,targetType,title})}>授权接收此会话</button></details>
    </div>
  </>
}
