import { useState } from 'react'

type Run = (action: string, data?: object) => Promise<unknown>
type App = {package: string; name: string}
export function GameSessionPanel({run, available}: {run: Run; available: boolean}) {
  const [apps,setApps] = useState<App[]>([]), [selected,setSelected] = useState('')
  const [goal,setGoal] = useState('陪我一起看剧情和探索，遇到选择先和我商量。')
  const [consent,setConsent] = useState(false), [control,setControl] = useState(false)
  const [password,setPassword] = useState(''), [busy,setBusy] = useState(false), [notice,setNotice] = useState('')
  async function load() {
    setBusy(true); setNotice('')
    try { setApps(await run('launchableApps') as App[]) } catch(e) { setNotice(e instanceof Error?e.message:String(e)) }
    finally { setBusy(false) }
  }
  async function start() {
    setBusy(true); setNotice('')
    try {
      await run('startGameCompanion', {packageName:selected,goal,observe:consent,singlePlayer:consent,control,password})
      setPassword(''); setNotice('已提交本次陪伴任务。可在浮球交流或停止输入；模型与游戏结果请看实际回执。')
    } catch(e) { setNotice(e instanceof Error?e.message:String(e)) }
    finally { setBusy(false) }
  }
  return <section className="card game-session" aria-label="开始单机游戏陪伴">
    <h2>一起进入游戏</h2><p>先选择本机应用，再说这次想一起做什么。默认观察与商量；操作需要另行勾选。</p>
    <button type="button" disabled={!available||busy} onClick={()=>void load()}>读取本机应用列表</button>
    <label>这次的单机游戏<select value={selected} onChange={e=>setSelected(e.target.value)} disabled={!available||busy}><option value="">请选择游戏</option>{apps.map(app=><option key={app.package} value={app.package}>{app.name}</option>)}</select></label>
    <label>这次一起做什么<textarea value={goal} onChange={e=>setGoal(e.target.value)} rows={3} maxLength={12000}/></label>
    <label className="check-row"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/>这是本机单人游戏；允许观察该应用十分钟，并将本次需要的画面发给已配置的模型</label>
    <label className="check-row"><input type="checkbox" checked={control} onChange={e=>setControl(e.target.checked)}/>另外允许小克按这次意愿操作两分钟；随时可仅停止输入</label>
    <details><summary>已有设备允许列表密码</summary><label>如已设置保护密码，在本机输入<input type="password" autoComplete="off" value={password} onChange={e=>setPassword(e.target.value)}/></label></details>
    <p className="subtle">所选应用会加入设备允许列表。本次输入仍受独立时限、锁屏与画面核对限制；原始画面默认临时保留。急停后只由你恢复。当前适合静态或可暂停场景，动画画面可能被拒绝操作，尚无指定游戏实战验收。</p>
    <button type="button" className="primary" disabled={!available||busy||!selected||!consent||!goal.trim()} onClick={()=>void start()}>{busy?'正在准备…':'开启这次陪伴'}</button>
    {notice&&<p role="status">{notice}</p>}
  </section>
}
