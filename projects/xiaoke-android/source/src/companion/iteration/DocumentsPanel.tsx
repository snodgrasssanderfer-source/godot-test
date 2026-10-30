import { useState } from 'react'
import { CircleNotch, FileText } from '@phosphor-icons/react'
import { nativeCompanion } from '../bridge'

export function DocumentsPanel({run,onAttach}:{run:(action:string,data?:object)=>Promise<unknown>;onAttach:(id:string)=>void}) {
  const [start,setStart]=useState(1),[pages,setPages]=useState(12),[busy,setBusy]=useState(false)
  const [result,setResult]=useState<{id?:string;source?:string;coverage?:string}|null>(null),[error,setError]=useState('')
  async function pick() {
    if (busy) return
    setBusy(true);setError('')
    try { setResult(await run('pickDocument',{startPage:start,pages}) as typeof result) }
    catch(e){setError(e instanceof Error?e.message:String(e))}
    finally{setBusy(false)}
  }
  return <div className="xk-stack-tight">
    <p className="xk-hint">文本、PDF 或 ZIP，读完附在下一条消息里。</p>
    <div className="xk-grid"><label>PDF 从第几页<input type="number" min={1} max={100000} value={start} onChange={e=>setStart(Number(e.target.value))}/></label><label>读几页<input type="number" min={1} max={12} value={pages} onChange={e=>setPages(Number(e.target.value))}/></label></div>
    <button type="button" className="xk-btn primary" disabled={!nativeCompanion||busy||!Number.isInteger(start)||start<1||pages<1||pages>12} onClick={()=>void pick()}>{busy?<CircleNotch size={18} className="xk-spin"/>:<FileText size={18} weight="duotone"/>}选择文件</button>
    {error&&<p role="alert" className="xk-error-text">{error}</p>}
    {result&&<div className="xk-card inset"><strong>{result.source}</strong><p className="xk-hint">{result.coverage}</p>{result.id&&<button type="button" className="xk-btn" onClick={()=>onAttach(result.id!)}>附上</button>}</div>}
  </div>
}
