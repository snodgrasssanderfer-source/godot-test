import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { GameSessionPanel } from './GameSessionPanel'

describe('单机陪伴入口',()=>{
  it('画面观察确认不会隐式授权输入',async()=>{
    const run=vi.fn<(action:string,data?:object)=>Promise<unknown>>().mockImplementation(action=>Promise.resolve(action==='launchableApps'?[{package:'example.game',name:'测试游戏'}]:{}))
    render(<GameSessionPanel run={run} available/>)
    expect(screen.getByText('开启这次陪伴')).toBeDisabled()
    fireEvent.click(screen.getByText('读取本机应用列表'))
    await screen.findByText('测试游戏')
    fireEvent.change(screen.getByLabelText('这次的单机游戏'),{target:{value:'example.game'}})
    fireEvent.click(screen.getByLabelText(/这是单机游戏/))
    fireEvent.click(screen.getByText('开启这次陪伴'))
    await waitFor(()=>expect(run).toHaveBeenCalledWith('startGameCompanion',expect.objectContaining({packageName:'example.game',observe:true,singlePlayer:true,control:false})))
  })
  it('权限拒绝不会显示成功或再次启动',async()=>{
    const run=vi.fn<(action:string,data?:object)=>Promise<unknown>>().mockImplementation(action=>{
      if(action==='launchableApps')return Promise.resolve([{package:'example.game',name:'测试游戏'}])
      return Promise.reject(new Error('请先开启浮窗权限'))
    })
    render(<GameSessionPanel run={run} available/>)
    fireEvent.click(screen.getByText('读取本机应用列表'));await screen.findByText('测试游戏')
    fireEvent.change(screen.getByLabelText('这次的单机游戏'),{target:{value:'example.game'}})
    fireEvent.click(screen.getByLabelText(/这是单机游戏/));fireEvent.click(screen.getByText('开启这次陪伴'))
    await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('请先开启浮窗权限'))
    expect(run.mock.calls.filter(c=>c[0]==='startGameCompanion')).toHaveLength(1)
    expect(screen.queryByText(/开始了/)).toBeNull()
  })
})
