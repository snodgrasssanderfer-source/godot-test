// Copy this file to src/companion/presence/PresencePanel.test.tsx in the host.
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PresencePanel } from './PresencePanel'
import type { PresenceState } from './contract'

vi.mock('./character-stage', () => ({ mountCharacterStage: () => ({ setVisible: vi.fn(), setArtwork: vi.fn(), dispose: vi.fn() }) }))
afterEach(cleanup)
const idle: PresenceState = { phase: 'idle', displayName: '小克', running: false, mode: 'companion' }

describe('PresencePanel host integration', () => {
  it('does not start perception on mount and sends only the explicit start configuration', async () => {
    const command = vi.fn().mockResolvedValue(undefined)
    render(<PresencePanel state={idle} onCommand={command}/>)
    expect(command).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    await waitFor(() => expect(command).toHaveBeenCalledWith('presenceStart', { package: '', minutes: 20, mode: 'companion', intervalSeconds: 15, voiceInput: false, voiceOutput: true }))
  })

  it('requires a selected package before screen observation and forwards exact settings', async () => {
    const command = vi.fn().mockResolvedValue(undefined)
    render(<PresencePanel state={idle} onCommand={command}/>)
    fireEvent.click(screen.getByLabelText('读取选定应用画面'))
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    expect(command).not.toHaveBeenCalled()
    expect(screen.getByRole('alert').textContent).toContain('应用包名')
    fireEvent.change(screen.getByRole('textbox', { name: /仅允许观察此应用/ }), { target: { value: 'com.example.game' } })
    fireEvent.click(screen.getByLabelText('允许本次持续听取语音'))
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    await waitFor(() => expect(command).toHaveBeenCalledWith('presenceStart', expect.objectContaining({ package: 'com.example.game', voiceInput: true })))
  })

  it('sends focus, interrupt, listen, mute and stop commands without optimistic running state', async () => {
    const command = vi.fn().mockResolvedValue(undefined)
    render(<PresencePanel state={{ ...idle, running: true, phase: 'speaking', muted: false }} onCommand={command}/>)
    fireEvent.click(screen.getByRole('button', { name: '专注陪伴' }))
    await waitFor(() => expect(command).toHaveBeenCalledWith('presenceMode', { mode: 'focus' }))
    fireEvent.click(screen.getByRole('button', { name: '打断当前回应' }))
    fireEvent.click(screen.getByRole('button', { name: '听我说一次' }))
    fireEvent.click(screen.getByRole('button', { name: '关闭语音输出' }))
    fireEvent.click(screen.getByRole('button', { name: '结束陪伴' }))
    await waitFor(() => expect(command).toHaveBeenCalledWith('presenceStop', undefined))
    expect(command).toHaveBeenCalledWith('presenceInterrupt', undefined)
    expect(command).toHaveBeenCalledWith('presenceListen', undefined)
    expect(command).toHaveBeenCalledWith('presenceMute', { muted: true })
    expect(screen.getByRole('status').textContent).toContain('正在说话')
  })

  it('keeps stop reachable while a start command waits for native permissions', async () => {
    let finish: () => void = () => {}
    const command = vi.fn((action: string) => action === 'presenceStart' ? new Promise<void>(resolve => { finish = resolve }) : Promise.resolve())
    render(<PresencePanel state={idle} onCommand={command}/>)
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    fireEvent.click(screen.getByRole('button', { name: '结束陪伴' }))
    expect(command).toHaveBeenCalledWith('presenceStop', undefined)
    await act(async () => { finish() })
  })

  it('reports native rejection and allows a retry', async () => {
    const command = vi.fn().mockRejectedValue(new Error('麦克风权限未授予'))
    render(<PresencePanel state={idle} onCommand={command}/>)
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('麦克风权限'))
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    await waitFor(() => expect(command).toHaveBeenCalledTimes(2))
  })

  it('does not render another memory domain text and gates unavailable native actions', () => {
    const command = vi.fn()
    render(<PresencePanel state={{ ...idle, scope: 'qq:group:3', text: 'private-other-domain', available: false }} onCommand={command}/>)
    expect(screen.queryByText('private-other-domain')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '开启本次陪伴' }))
    expect(command).not.toHaveBeenCalled()
  })
})
