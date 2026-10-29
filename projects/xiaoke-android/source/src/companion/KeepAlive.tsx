import { nativeCompanion, type Snapshot } from './bridge'

type Run = (action: string, data?: object) => Promise<unknown>

/** Android 16 and phone makers kill background apps. These steps keep her around; each is one tap and a system confirmation. */
export function KeepAlive({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const p = snapshot.permissions, s = snapshot.settings
  const go = (action: string, data: object = {}) => void run(action, data).catch(() => undefined)
  const steps: [string, string, boolean, () => void][] = [
    ['允许通知', '常驻通知和她的消息提醒都靠它', p.postNotifications === true, () => go('notificationPermission')],
    ['允许悬浮窗', '悬浮球和随手打字聊天', p.overlay, () => go('openPermission', {kind: 'overlay'})],
    ['电池优化：不限制', '弹出的系统对话框里点“允许”', p.batteryExempt === true, () => go('requestBatteryExemption')],
    ['允许自启动', '打开后找到“小克”，把开关打开，再回来点“已打开”', s.autostartConfirmed === true, () => go('openAutostart')],
    ['后台高耗电（部分手机有）', '找到“小克”，允许后台高耗电或后台活动', s.autostartConfirmed === true, () => go('openBackgroundPower')],
    ['后台常驻', '被系统回收后自己回来，重启后第一次解锁时恢复', s.backgroundEnabled, () => go('settings', {...s, backgroundEnabled: true})],
    ['显示悬浮球', '点开就能打字和她聊', snapshot.overlaySession?.running === true, () => go('module', {domain: 'device', action: 'overlay.visibility', data: {visible: true}})],
  ]
  return <div className="card"><h2>让她一直在</h2>
    <div className="permission-list">{steps.map(([name, hint, done, act]) => <div className="record-row" key={name}>
      <div><strong>{name} <span className={done ? 'saved' : 'subtle'}>{done ? '已完成' : '待设置'}</span></strong><small>{hint}</small></div>
      <button disabled={!nativeCompanion} onClick={act}>{done ? '再看看' : '去设置'}</button>
    </div>)}</div>
    {!s.autostartConfirmed && <button disabled={!nativeCompanion} onClick={() => go('settings', {...s, autostartConfirmed: true})}>自启动和后台耗电都已打开</button>}
  </div>
}
