import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SocialPanel } from './SocialPanel'

describe('SocialPanel', () => {
  it('binds a batch approval to the displayed content digest', async () => {
    const run = vi.fn().mockResolvedValue({items:[]})
    render(<SocialPanel available run={run} state={{approvals:[{id:'draft-1',digest:'exact-content-digest',state:'pending',effect:'public_comment',target:'feed-1',account:'10001',content:'具体评论内容',expiresAt:2000000000000}]}}/> )
    fireEvent.click(screen.getByLabelText(/public_comment/))
    fireEvent.click(screen.getByRole('button',{name:'批准所选目标与内容'}))
    await waitFor(()=>expect(run).toHaveBeenCalledWith('approvals.approveBatch',{items:[{id:'draft-1',digest:'exact-content-digest'}]}))
  })
  it('shows an unknown outcome for explicit reconciliation without a retry button', () => {
    render(<SocialPanel available run={vi.fn()} state={{activity:[{id:'unknown',effect:'public_post',state:'outcome_unknown',detail:'等待平台查证'}]}}/> )
    expect(screen.getByText(/结果待核对/)).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'已查证成功'})).toBeInTheDocument()
    expect(screen.queryByRole('button',{name:/重试发送/})).not.toBeInTheDocument()
  })
  it('surfaces permission failure instead of claiming the task was saved', async () => {
    const run=vi.fn().mockRejectedValue(new Error('活动额度不足'))
    render(<SocialPanel available run={run} state={{}}/> )
    fireEvent.change(screen.getByLabelText('内容'),{target:{value:'需要整理的资料'}})
    fireEvent.click(screen.getByRole('button',{name:'保存任务'}))
    await waitFor(()=>expect(screen.getByRole('alert')).toHaveTextContent('活动额度不足'))
    expect(screen.queryByText('状态已保存')).not.toBeInTheDocument()
  })
  it('blocks duplicate clicks while a real operation is unresolved', async () => {
    let resolve!:(value:unknown)=>void
    const run=vi.fn().mockImplementation(()=>new Promise(r=>{resolve=r}))
    render(<SocialPanel available run={run} state={{sync:{enabled:true}}}/> )
    const button=screen.getByRole('button',{name:'同步一次'})
    fireEvent.click(button);fireEvent.click(button)
    expect(run).toHaveBeenCalledTimes(1)
    resolve({status:'done'})
    await waitFor(()=>expect(button).not.toBeDisabled())
  })
  it('does not expose usable operations when the native module is unavailable', () => {
    render(<SocialPanel available={false} run={vi.fn()} state={{}}/> )
    expect(screen.getByRole('button',{name:'保存并启用加密同步'})).toBeDisabled()
    expect(screen.getByText(/尚未接入社交模块/)).toBeInTheDocument()
  })
})
