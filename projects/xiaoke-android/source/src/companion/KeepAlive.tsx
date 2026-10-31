import { Check } from '@phosphor-icons/react'
import { nativeCompanion, type Run, type Snapshot } from './bridge'

/** Android 16 and phone makers kill background apps. These steps keep her around; each is one tap and a system confirmation. */
export function KeepAlive({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const p = snapshot.permissions, s = snapshot.settings
  const go = (action: string, data: object = {}) => void run(action, data).catch(() => undefined)
  const steps: [string, string, boolean, () => void][] = [
    ['允许通知', '常驻通知和她的提醒', p.postNotifications === true, () => go('notificationPermission')],
    ['允许悬浮窗', '悬浮球，随手就能聊', p.overlay, () => go('openPermission', {kind: 'overlay'})],
    ['电池不限制', '弹窗里点“允许”', p.batteryExempt === true, () => go('requestBatteryExemption')],
    ['允许自启动', '找到“小克”打开开关', s.autostartConfirmed === true, () => go('openAutostart')],
    ['允许后台耗电', '部分手机才有这一项', s.autostartConfirmed === true, () => go('openBackgroundPower')],
    ['后台常驻', '被清掉后自己回来', s.backgroundEnabled, () => go('settings', {...s, backgroundEnabled: true})],
    ['显示悬浮球', '', snapshot.overlaySession?.running === true, () => go('module', {domain: 'device', action: 'overlay.visibility', data: {visible: true}})],
  ]
  const done = steps.filter(step => step[2]).length
  return <div className="xk-card xk-keepalive">
    <div className="xk-card-head"><h2>让她一直在</h2><span className={'xk-pill' + (done === steps.length ? ' ok' : '')}>{done} / {steps.length}</span></div>
    <div className="xk-meter" aria-hidden="true"><span style={{width: `${done / steps.length * 100}%`}}/></div>
    <div className="xk-list">{steps.map(([name, hint, ok, act]) => <button type="button" className={'xk-row-btn xk-step-row' + (ok ? ' ok' : '')} key={name} disabled={!nativeCompanion} onClick={act}>
      <span className="xk-check-dot">{ok && <Check size={14} weight="bold"/>}</span>
      <span><strong>{name}</strong>{hint && <small>{hint}</small>}</span>
      <span className="xk-state">{ok ? '好了' : '去设置'}</span>
    </button>)}</div>
    {!s.autostartConfirmed && <button className="xk-btn" disabled={!nativeCompanion} onClick={() => go('settings', {...s, autostartConfirmed: true})}>自启动和后台耗电都开好了</button>}
  </div>
}

/** What lets her see and act on the phone: overlay, accessibility, Shizuku, microphone. */
export function PhoneAccess({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const p = snapshot.permissions
  const go = (action: string, data: object = {}) => void run(action, data).catch(() => undefined)
  const accessibility = p.accessibility.serviceStatus === 'connected' || p.accessibility.serviceStatus === 'enabled'
  const steps: [string, string, boolean, () => void][] = [
    ['悬浮窗', '悬浮球，在别的应用里也能找她', p.overlay, () => go('openPermission', {kind: 'overlay'})],
    ['无障碍', '看界面、帮你点；陪你玩游戏要用', accessibility, () => go('openPermission', {kind: 'accessibility'})],
    ['Shizuku', p.shizuku === 'no_permission' ? 'Shizuku 已在运行，点一下授权' : '更顺手的游戏操作和后台代玩（可选）', p.shizuku === 'ready', () => go('openPermission', {kind: 'shizuku'})],
    ['麦克风', '语音陪伴时听你说话', p.microphone === true, () => go('microphonePermission')],
  ]
  const done = steps.filter(step => step[2]).length
  return <div className="xk-card xk-keepalive">
    <div className="xk-card-head"><h2>手机权限</h2><span className={'xk-pill' + (done === steps.length ? ' ok' : '')}>{done} / {steps.length}</span></div>
    <div className="xk-list">{steps.map(([name, hint, ok, act]) => <button type="button" className={'xk-row-btn xk-step-row' + (ok ? ' ok' : '')} key={name} disabled={!nativeCompanion} onClick={act}>
      <span className="xk-check-dot">{ok && <Check size={14} weight="bold"/>}</span>
      <span><strong>{name}</strong><small>{hint}</small></span>
      <span className="xk-state">{ok ? '好了' : '去设置'}</span>
    </button>)}</div>
  </div>
}
