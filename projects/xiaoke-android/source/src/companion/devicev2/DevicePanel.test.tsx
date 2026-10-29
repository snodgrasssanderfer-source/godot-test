import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DevicePanel } from './DevicePanel'

describe('设备控制面板', () => {
  it('急停按钮只发输入停止，长动作进行时仍可用', () => {
    const run = vi.fn((action: string) => action === 'capture.request' ? new Promise<unknown>(() => {}) : Promise.resolve({}))
    render(<DevicePanel state={{}} run={run} available />)
    fireEvent.change(screen.getByLabelText('目标应用包名'), { target: { value: 'example.game' } })
    fireEvent.click(screen.getByText('打开应用并观察一帧'))
    fireEvent.click(screen.getByText('仅停止输入'))
    expect(run.mock.calls.map(c => c[0])).toEqual(['capture.request', 'control.stop'])
  })
  it('音频配置失败时不会继续启动录音，也不会伪造成功', async () => {
    const run = vi.fn<(action: string, data?: Record<string, unknown>) => Promise<unknown>>().mockRejectedValue(new Error('配置拒绝'))
    render(<DevicePanel state={{}} run={run} available />)
    fireEvent.change(screen.getByLabelText('目标应用包名'), { target: { value: 'example.game' } })
    fireEvent.click(screen.getByText('系统授权后采集二十秒'))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('配置拒绝'))
    expect(run).toHaveBeenCalledTimes(1)
    expect(run.mock.calls[0][0]).toBe('audio.configure')
  })
  it('隐藏浮球和打断语音使用互不关联的入口', async () => {
    const run = vi.fn<(action: string, data?: Record<string, unknown>) => Promise<unknown>>().mockResolvedValue({})
    render(<DevicePanel state={{ overlay: { visible: true } }} run={run} available />)
    fireEvent.click(screen.getByText('隐藏浮球'))
    await waitFor(() => expect(run).toHaveBeenCalledWith('overlay.visibility', { visible: false }))
    fireEvent.click(screen.getByText('打断当前语音回应'))
    expect(run.mock.calls.map(c => c[0])).toEqual(['overlay.visibility', 'speech.interrupt'])
  })
  it('浏览器明确不可操作手机', () => {
    render(<DevicePanel state={{}} run={vi.fn()} available={false} />)
    expect(screen.getByText('仅停止输入')).toBeDisabled()
    expect(screen.getByText(/浏览器没有连接手机/)).toBeVisible()
  })
})
