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
      setPassword(''); setNotice('开始了，浮球上随时能叫停。')
    } catch(e) { setNotice(e instanceof Error?e.message:String(e)) }
    finally { setBusy(false) }
  }
  return <section className="xk-stack-tight game-session" aria-label="开始单机游戏陪伴">
    <div className="xk-inline-form">
      <label>这次的单机游戏<select value={selected} onChange={e=>setSelected(e.target.value)} disabled={!available||busy}><option value="">{apps.length?'选一个':'先读取应用'}</option>{apps.map(app=><option key={app.package} value={app.package}>{app.name}</option>)}</select></label>
      <button type="button" className="xk-btn" disabled={!available||busy} onClick={()=>void load()}>读取本机应用列表</button>
    </div>
    <label>这次一起做什么<textarea value={goal} onChange={e=>setGoal(e.target.value)} rows={2} maxLength={12000}/></label>
    <label className="xk-switch-row"><span>让她看画面<small>单机游戏，十分钟</small></span><input type="checkbox" aria-label="这是单机游戏，允许她看画面" checked={consent} onChange={e=>setConsent(e.target.checked)}/></label>
    <label className="xk-switch-row"><span>让她上手操作<small>两分钟，随时能停</small></span><input type="checkbox" aria-label="允许她操作" checked={control} onChange={e=>setControl(e.target.checked)}/></label>
    <details className="xk-more"><summary>设备控制密码</summary><label>设过才需要填<input type="password" autoComplete="off" value={password} onChange={e=>setPassword(e.target.value)}/></label></details>
    <button type="button" className="xk-btn primary" disabled={!available||busy||!selected||!consent||!goal.trim()} onClick={()=>void start()}>{busy?'正在准备…':'开启这次陪伴'}</button>
    {notice&&<p role="status">{notice}</p>}
  </section>
}
