import { useState } from 'react'
import { ArrowLeft, Check, ChevronRight, KeyRound, Shield } from 'lucide-react'
import { nativeCompanion, type Snapshot } from './bridge'

type Run = (action: string, data?: object) => Promise<unknown>
export function Onboarding({snapshot, run, onClose, onOpen}: {snapshot: Snapshot; run: Run; onClose: () => void; onOpen: (tab: 'models'|'persona'|'device'|'memory') => void}) {
  const [step, setStep] = useState(0), [endpoint, setEndpoint] = useState(snapshot.settings.endpoint)
  const [model, setModel] = useState(snapshot.settings.activeModel), [key, setKey] = useState('')
  const [online, setOnline] = useState(snapshot.settings.networkEnabled), [saving, setSaving] = useState(false), [note, setNote] = useState('')
  const titles = ['先认识你的助手', '连接你的模型', '把角色交给你', '按需开启手机能力', '决定记忆和外发范围']
  const p = snapshot.permissions
  async function saveConnection() {
    setSaving(true); setNote('')
    try {
      await run('settings', {...snapshot.settings, endpoint, activeModel: model, networkEnabled: online})
      if (key.trim()) { await run('saveKey', {key: key.trim()}); setKey('') }
      setNote('已保存配置。没有自动调用模型，也没有验证中转能力。')
    } catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  async function finish() {
    setSaving(true)
    try { await run('settings', {...snapshot.settings, onboardingCompleted: true}); onClose() }
    catch (e) { setNote(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  const open = (tab: 'models'|'persona'|'device'|'memory') => { onClose(); onOpen(tab) }
  return <section className="content-panel onboarding"><div className="companion-heading"><span className="pill">安装后设置 · {step + 1} / {titles.length}</span><h1>{titles[step]}</h1><p>设置可以随时回来修改；不需要一次授予所有权限。</p></div>
    <ol className="onboarding-steps" aria-label="设置进度">{titles.map((title, i) => <li key={title}><button aria-current={i === step ? 'step' : undefined} className={i === step ? 'selected' : ''} onClick={() => setStep(i)}>{i < step ? <Check size={15}/> : i + 1}<span>{title}</span></button></li>)}</ol>
    <div className="card">
      {step === 0 && <><h2>一个由你掌握范围的 AI 助手</h2><p>本机私聊、人设和记忆是同一套。QQ 通过独立 NapCat 通道收发，不争用你手机上的 QQ；B站和设备任务保留安卓操作。</p><p>游戏陪伴由你开启一次限时会话，可以全程交流、暂停和静音。截图、语音和外发分别有自己的开关。</p><p>原 DSH 工作台继续提供文件、终端和插件。它的运行时需要首次初始化；小克的本机页面可先使用。</p></>}
      {step === 1 && <><h2><KeyRound size={20}/> 原生 Gemini 连接</h2><label>API 基础地址<input value={endpoint} onChange={e => setEndpoint(e.target.value)} inputMode="url" autoCapitalize="none" spellCheck={false}/></label>
        <label>模型<select value={model} onChange={e => setModel(e.target.value)}>{snapshot.settings.profiles.map(p => <option key={p.model}>{p.model}</option>)}</select></label>
        <label>本机密钥<input type="password" autoComplete="new-password" value={key} disabled={!nativeCompanion} onChange={e => setKey(e.target.value)} placeholder={snapshot.keyConfigured ? '已安全保存，留空保持' : '安装后在这里填写，不需要发到聊天中'}/></label>
        <label className="check-row"><input type="checkbox" checked={online} disabled={!nativeCompanion} onChange={e => setOnline(e.target.checked)}/>允许助手按我的操作联网</label>
        <p className="subtle">地址、模型名及图片/工具能力需符合你的中转。保存不会发起测试请求；后续每次模型交互可能计费，日限额可在模型页设置。</p>
        <div className="form-actions"><button className="primary" disabled={saving} onClick={() => void saveConnection()}>保存连接配置</button><button onClick={() => open('models')}>更多模型设置</button></div></>}
      {step === 2 && <><h2>你和 Gemini 一起写小克</h2><p>人格正文保持可编辑，不在安装包写死性格或台词。你可以在连接模型后共同创作，也可以导入自己的角色卡。</p><p>角色版本和长期记忆分别保存；切换模型不会自动清空关系和偏好。资料中的设定只是参考，不能把角色卡当成设备权限。</p><button onClick={() => open('persona')}>打开角色编辑与导入</button></>}
      {step === 3 && <><h2>让你需要的能力可用</h2><p>仅聊天无需无障碍或屏幕权限。观察选定应用才开启无障碍和悬浮窗；语音需要麦克风；NapCat 不需要手机 QQ 的无障碍操作。</p><div className="permission-list">{[
          ['accessibility', '无障碍', p.accessibility.serviceStatus === 'connected' || p.accessibility.serviceStatus === 'enabled'],
          ['overlay', '悬浮窗', p.overlay],
          ['microphone', '麦克风', p.microphone === true],
          ['notificationPermission', '运行通知', p.postNotifications === true],
        ].map(([kind, name, ready]) => <div key={String(kind)}><span>{name}<small>{ready ? '已授权' : '按需要开启'}</small></span><button disabled={!nativeCompanion || saving} onClick={() => void run(kind === 'microphone' ? 'microphonePermission' : kind === 'notificationPermission' ? 'notificationPermission' : 'openPermission', {kind}).catch(() => undefined)}>{ready ? '查看' : '设置'}</button></div>)}</div><button onClick={() => open('device')}>查看设备、后台与连接诊断</button></>}
      {step === 4 && <><h2><Shield size={20}/> 先定边界，再让它行动</h2><p>QQ 会话逐个授权接收，默认不主动发言；每个群的记忆独立。本人 QQ 私聊共享主人记忆，也要核对数字账号后单独开启。</p><p>评论和设备提交先展示目标、账号、正文与依据。结果不明时停下核对，不自动重发。</p><p>游戏中的临时感知不自动变成长期记忆。重要事件由你点保存；记忆可更正、删除、加密导出和恢复。备份口令由你保管。</p><div className="form-actions"><button onClick={() => open('memory')}>管理记忆与备份</button><button onClick={() => open('device')}>设置 QQ 和 B站</button></div></>}
      {note && <p role="status">{note}</p>}
      <div className="form-actions onboarding-actions"><button disabled={step === 0 || saving} onClick={() => setStep(step - 1)}><ArrowLeft size={16}/> 上一步</button>{step < titles.length - 1 ? <button className="primary" disabled={saving} onClick={() => setStep(step + 1)}>下一步 <ChevronRight size={16}/></button> : <button className="primary" disabled={saving} onClick={() => void finish()}>完成设置，进入助手</button>}<button onClick={onClose} disabled={saving}>稍后设置</button></div>
    </div>
  </section>
}
