import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SocialPanel } from './SocialPanel'

describe('SocialPanel', () => {
  it('approves one drafted item with the digest it showed, or deletes it', async () => {
    const run = vi.fn().mockResolvedValue({items:[]})
    render(<SocialPanel available run={run} state={{approvals:[{id:'draft-1',kind:'bili_comment',digest:'exact-content-digest',state:'pending',effect:'public_comment',payload:{title:'测试视频'},content:'具体评论内容',motivation:'看完想说的',expiresAt:2000000000000}]}}/> )
    expect(screen.getByText('B站评论 · 《测试视频》')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'放行'}))
    await waitFor(()=>expect(run).toHaveBeenCalledWith('approvals.approveBatch',{items:[{id:'draft-1',digest:'exact-content-digest'}]}))
    fireEvent.click(screen.getByRole('button',{name:'删掉'}))
    await waitFor(()=>expect(run).toHaveBeenCalledWith('approvals.reject',{id:'draft-1'}))
  })
  it('loads a proposed picture only when asked', async () => {
    const run = vi.fn().mockResolvedValue({mime:'image/png',base64:'AAAA'})
    render(<SocialPanel available run={run} state={{approvals:[{id:'save-1',kind:'save_media',digest:'d',state:'pending',effect:'save',payload:{mediaIds:['media-1']},content:'示意图',expiresAt:2000000000000}]}}/> )
    fireEvent.click(screen.getByRole('button',{name:'看图'}))
    await waitFor(()=>expect(screen.getByAltText('示意图')).toBeInTheDocument())
    expect(run).toHaveBeenCalledWith('media.preview',{id:'media-1'})
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
    expect(screen.getByText(/社交模块还没连上/)).toBeInTheDocument()
  })
})
