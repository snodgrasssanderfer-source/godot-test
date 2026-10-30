import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LifePanel } from './LifePanel'

const fixture = {
  memories: [{ id: 'fixture-memory', scope: 'owner:local', text: '一条测试记忆', revision: 3, kind: 'fact', factKey: 'fixture', createdAt: 1000 }],
  diary: [], interests: [], attention: { mode: 'available' }, budgets: { configured: false },
}

describe('生活面板的真实命令边界', () => {
  it('不可用时不会模拟成功或发出保存命令', () => {
    const run = vi.fn()
    render(<LifePanel state={fixture} available={false} run={run} />)
    fireEvent.change(screen.getByLabelText('记录内容'), { target: { value: 'fixture' } })
    fireEvent.click(screen.getByRole('button', { name: '保存记录' }))
    expect(run).not.toHaveBeenCalled()
    expect(screen.getByText(/生活模块还没连上/)).toBeVisible()
  })

  it('纠正携带当前修订号并显示权限或冲突错误', async () => {
    const run = vi.fn().mockRejectedValue(new Error('记录已被修改，请重新加载后纠正'))
    render(<LifePanel state={fixture} available run={run} />)
    fireEvent.click(screen.getByRole('button', { name: '纠正' }))
    fireEvent.change(screen.getByLabelText('记录内容'), { target: { value: '纠正后的测试记忆' } })
    fireEvent.click(screen.getByRole('button', { name: '保存纠正' }))
    await screen.findByRole('alert')
    expect(run).toHaveBeenCalledWith('memory.upsert', expect.objectContaining({ id: 'fixture-memory', expectedRevision: 3, text: '纠正后的测试记忆' }))
    expect(screen.getByLabelText('记录内容')).toHaveValue('纠正后的测试记忆')
    expect(screen.getByText('一条测试记忆')).toBeVisible()
  })

  it('忘掉使用独立动作且阻止等待中的重复点击', async () => {
    let resolve: (value: unknown) => void = () => undefined
    const run = vi.fn().mockImplementation((action: string) => action === 'memory.search' ? Promise.resolve({ items: [] }) : new Promise(done => { resolve = done }))
    render(<LifePanel state={fixture} available run={run} />)
    const button = screen.getByRole('button', { name: '忘掉' })
    fireEvent.click(button); fireEvent.click(button)
    expect(run).toHaveBeenCalledTimes(1)
    expect(run).toHaveBeenCalledWith('memory.forget', { id: 'fixture-memory', scope: 'owner:local', forgotten: true })
    await act(async () => { resolve({ forgotten: true }); await Promise.resolve() })
    expect(screen.getByRole('status')).toHaveTextContent('操作完成')
  })

  it('删除先明确来源和派生影响，再发出彻底删除命令', async () => {
    const run = vi.fn().mockResolvedValue({ deleted: [] })
    render(<LifePanel state={fixture} available run={run} />)
    fireEvent.click(screen.getByRole('button', { name: '删除' }))
    expect(run).not.toHaveBeenCalled()
    expect(screen.getByRole('group', { name: '确认删除' })).toHaveTextContent('来源消息和所有派生记录')
    fireEvent.click(screen.getByRole('button', { name: '确认彻底删除' }))
    await waitFor(() => expect(run).toHaveBeenCalledWith('memory.delete', { id: 'fixture-memory', scope: 'owner:local' }))
  })

  it('额度配置不会默默授权未知价格', async () => {
    const run = vi.fn().mockResolvedValue({})
    render(<LifePanel state={fixture} available run={run} />)
    fireEvent.click(screen.getByRole('button', { name: '额度' }))
    fireEvent.click(screen.getByRole('button', { name: '保存额度' }))
    await waitFor(() => expect(run).toHaveBeenCalledWith('budget.configure', expect.objectContaining({ ownerLimit: 80, activityLimit: 20, allowUnknownPrice: false, unknownPriceCeiling: 0 })))
  })

  it('不打扰按钮传结构化到期状态而不执行停机指令', async () => {
    const run = vi.fn().mockResolvedValue({})
    render(<LifePanel state={fixture} available run={run} />)
    fireEvent.click(screen.getByRole('button', { name: '注意力' }))
    const before = Date.now()
    fireEvent.click(screen.getByRole('button', { name: '这段时间别打扰我' }))
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1))
    expect(run.mock.calls[0][0]).toBe('attention.set')
    expect(run.mock.calls[0][1]).toEqual(expect.objectContaining({ mode: 'busy', maxBackgroundTasks: 2 }))
    const payload: unknown = run.mock.calls[0][1]
    expect((payload as { until: number }).until).toBeGreaterThanOrEqual(before + 30 * 60000)
  })

  it('跨会话搜索保留明确范围且日记不能凭空新增', async () => {
    const run = vi.fn().mockResolvedValue({ items: [] })
    render(<LifePanel state={fixture} available run={run} />)
    fireEvent.change(screen.getByLabelText('会话范围'), { target: { value: 'qq:group:fixture' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(run).toHaveBeenCalledWith('memory.search', expect.objectContaining({ scope: 'qq:group:fixture' })))
    await waitFor(() => expect(screen.getByRole('button', { name: '日记' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '日记' }))
    expect(screen.queryByLabelText('记录内容')).toBeNull()
    expect(screen.getByText(/真实发生的事/)).toBeVisible()
  })

  it('宿主轮询不覆盖跨会话搜索，但内容删除会清除旧结果', async () => {
    const run = vi.fn().mockResolvedValue({ items: [{ id: 'group-memory', scope: 'qq:group:fixture', text: '群聊测试记录' }] })
    const { rerender } = render(<LifePanel state={{ ...fixture, contentEpoch: 1 }} available run={run} />)
    fireEvent.change(screen.getByLabelText('会话范围'), { target: { value: 'qq:group:fixture' } })
    expect(screen.queryByText('一条测试记忆')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await screen.findByText('群聊测试记录')
    rerender(<LifePanel state={{ ...fixture, memories: [...fixture.memories], contentEpoch: 1 }} available run={run} />)
    expect(screen.getByText('群聊测试记录')).toBeVisible()
    rerender(<LifePanel state={{ ...fixture, memories: [], contentEpoch: 2 }} available run={run} />)
    await waitFor(() => expect(screen.queryByText('群聊测试记录')).toBeNull())
  })

  it('预算使用量轮询不会覆盖主人正在填写的额度', () => {
    const budgets = { configured: true, unit: 'calls', owner: { limit: 80, spent: 1 }, activity: { limit: 20 }, warningRemaining: 2 }
    const run = vi.fn()
    const { rerender } = render(<LifePanel state={{ ...fixture, budgets }} available run={run} />)
    fireEvent.click(screen.getByRole('button', { name: '额度' }))
    fireEvent.change(screen.getByLabelText('主人额度'), { target: { value: '123' } })
    rerender(<LifePanel state={{ ...fixture, budgets: { ...budgets, owner: { limit: 80, spent: 2 } } }} available run={run} />)
    expect(screen.getByLabelText('主人额度')).toHaveValue(123)
  })

  it('加密文件只交给系统保存入口，取消保存不会报成功', async () => {
    const run = vi.fn().mockResolvedValue({ text: 'encrypted-fixture-only' })
    const saveFile = vi.fn().mockRejectedValue(new Error('已取消保存，原数据保留'))
    render(<LifePanel state={fixture} available run={run} saveFile={saveFile} />)
    fireEvent.click(screen.getByRole('button', { name: '备份' }))
    fireEvent.change(screen.getByLabelText('备份口令'), { target: { value: 'fixture-password' } })
    fireEvent.click(screen.getByRole('button', { name: '生成加密备份' }))
    await waitFor(() => expect(screen.getByLabelText('加密备份内容')).toHaveValue('encrypted-fixture-only'))
    expect(screen.getByLabelText('备份口令')).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: '保存加密文件' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('已取消保存')
    expect(saveFile).toHaveBeenCalledWith('小克加密备份.json', 'encrypted-fixture-only')
    expect(screen.queryByText('已保存加密文件')).toBeNull()
  })
})
