import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { CaretRight, X } from '@phosphor-icons/react'

/** Collapsible card. Content stays mounted (collapsed by CSS) so form state survives and tests can reach it. */
export function Section({icon, title, summary, badge, defaultOpen = false, children}: {icon?: ReactNode; title: string; summary?: ReactNode; badge?: ReactNode; defaultOpen?: boolean; children: ReactNode}) {
  const [open, setOpen] = useState(defaultOpen)
  const id = useId()
  return <section className={'xk-section' + (open ? ' open' : '')}>
    <button type="button" className="xk-section-head" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
      {icon && <span className="xk-section-icon">{icon}</span>}
      <span className="xk-section-title"><strong>{title}</strong>{summary && <small>{summary}</small>}</span>
      {badge}
      <CaretRight className="xk-section-caret" size={18} weight="bold"/>
    </button>
    <div className="xk-collapse" id={id}><div className="xk-collapse-inner"><div className="xk-section-body">{children}</div></div></div>
  </section>
}

/** Bottom sheet; stays mounted for the exit animation. */
export function Sheet({open, title, onClose, children}: {open: boolean; title: string; onClose: () => void; children: ReactNode}) {
  const [mounted, setMounted] = useState(open)
  useEffect(() => {
    if (open) { setMounted(true); return }
    const timer = window.setTimeout(() => setMounted(false), 260)
    return () => window.clearTimeout(timer)
  }, [open])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!mounted) return null
  // Portal to the app root: page wrappers animate, and an animated ancestor would trap the fixed layer under the tab bar.
  return createPortal(<div className={'xk-sheet-layer' + (open ? ' open' : '')}>
    <div className="xk-sheet-backdrop" onClick={onClose}/>
    <div className="xk-sheet" role="dialog" aria-modal="true" aria-label={title}>
      <div className="xk-sheet-grip"/>
      <header className="xk-sheet-head"><h2>{title}</h2><button type="button" className="xk-icon-btn" aria-label="关闭" onClick={onClose}><X size={20} weight="bold"/></button></header>
      <div className="xk-sheet-body">{children}</div>
    </div>
  </div>, document.querySelector('.xk-app') ?? document.body)
}

/** Error toast; fades out on its own after a while, or on tap. */
export function Toast({message, onClose}: {message: string; onClose: () => void}) {
  const close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => close.current(), 9000)
    return () => window.clearTimeout(timer)
  }, [message])
  if (!message) return null
  return <div className="xk-toast" role="alert" key={message}><span>{message}</span><button type="button" aria-label="关闭错误提示" onClick={onClose}><X size={16} weight="bold"/></button></div>
}

export function Segmented<T extends string>({value, options, onChange, label}: {value: T; options: [T, string][]; onChange: (value: T) => void; label: string}) {
  const index = Math.max(0, options.findIndex(([v]) => v === value))
  return <div className="xk-segmented" role="tablist" aria-label={label} style={{'--count': options.length, '--index': index} as CSSProperties}>
    <span className="xk-segmented-thumb" aria-hidden="true"/>
    {options.map(([v, text]) => <button type="button" role="tab" key={v} aria-selected={v === value} onClick={() => onChange(v)}>{text}</button>)}
  </div>
}

export function PageHeader({title, children}: {title: string; children?: ReactNode}) {
  return <header className="xk-page-head"><h1>{title}</h1>{children}</header>
}

export function Avatar({name, size = 40, live = false}: {name: string; size?: number; live?: boolean}) {
  return <span className={'xk-avatar' + (live ? ' live' : '')} style={{width: size, height: size, fontSize: size * .44}} aria-hidden="true">{(name || '克').slice(-1)}</span>
}

export function Empty({icon, title, children}: {icon: ReactNode; title: string; children?: ReactNode}) {
  return <div className="xk-empty"><span className="xk-empty-icon">{icon}</span><strong>{title}</strong>{children && <p>{children}</p>}</div>
}
