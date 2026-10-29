import { useState } from 'react'
import { FileText, Loader2 } from 'lucide-react'
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
  return <details className="document-reader"><summary><FileText size={15}/> 一起看资料</summary>
    <p>选择文本、PDF 或 ZIP。先在本机读取，附加到消息后才随该消息发给模型。</p>
    <div className="form-grid"><label>PDF 起始页<input type="number" min={1} max={100000} value={start} onChange={e=>setStart(Number(e.target.value))}/></label><label>本次页数<input type="number" min={1} max={12} value={pages} onChange={e=>setPages(Number(e.target.value))}/></label></div>
    <button type="button" disabled={!nativeCompanion||busy||!Number.isInteger(start)||start<1||pages<1||pages>12} onClick={()=>void pick()}>{busy?<Loader2 size={15} className="spin"/>:<FileText size={15}/>}选择并读取文件</button>
    <small>PDF 读取可提取文字，不自动 OCR；ZIP 只读取其中的文本，未解析的文件会列明。临时资料约十分钟后或进程退出时清除。</small>
    {error&&<p role="alert" className="error-text">{error}</p>}
    {result&&<div className="document-result"><strong>{result.source}</strong><p>{result.coverage}</p>{result.id&&<button type="button" onClick={()=>onAttach(result.id!)}>附加到下一条消息</button>}</div>}
  </details>
}
