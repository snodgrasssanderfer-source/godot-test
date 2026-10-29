import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { BilibiliPanel } from './BilibiliPanel'

it('binds a comment proposal to the inspected video without implicitly approving it', async () => {
  const act = vi.fn().mockImplementation((action: string) => Promise.resolve(action === 'bili.inspect'
    ? { id: 'resource-confirmed', title: '合成视频', videoId: 'BV1234567890', upMid: '20002', upName: '合成UP主' }
    : { state: 'pending' }))
  render(<BilibiliPanel state={{ connected: true }} config={{}} disabled={false} act={act}/>)
  fireEvent.change(screen.getByLabelText('视频 BV 编号'), { target: { value: 'BV1234567890' } })
  fireEvent.click(screen.getByRole('button', { name: '读取并核对视频目标' }))
  await screen.findByText('合成视频')
  fireEvent.change(screen.getByLabelText('互动方式'), { target: { value: 'comment' } })
  fireEvent.change(screen.getByLabelText('拟发评论'), { target: { value: '合成拟发评论' } })
  fireEvent.change(screen.getByLabelText('为什么想这样做'), { target: { value: '讨论这个明确的主题' } })
  fireEvent.click(screen.getByRole('button', { name: '生成此目标的具体提议' }))
  await waitFor(() => expect(act).toHaveBeenCalledWith('bili.plan', expect.objectContaining({
    kind: 'comment', resourceId: 'resource-confirmed', content: '合成拟发评论', motivation: '讨论这个明确的主题',
  })))
  expect(act).toHaveBeenCalledTimes(2)
  expect(act).toHaveBeenNthCalledWith(1, 'bili.inspect', { videoId: 'BV1234567890' })
})
