import { expect, it } from 'vitest'
import { cardExtras } from './characterCard'

it('keeps post-history instructions, depth prompt and lorebook from a V2 card', () => {
  const extras = cardExtras({
    name: '示例', post_history_instructions: '保持口语',
    extensions: { depth_prompt: { prompt: '别像客服', depth: 2, role: 'system' } },
    character_book: { name: '书', entries: [{ keys: ['猫'], secondary_keys: [], content: '她养过一只猫', constant: false, selective: false, enabled: true, insertion_order: 5 }] },
  })
  expect(extras.postHistory).toBe('保持口语')
  expect(extras.depthPrompt).toEqual({ prompt: '别像客服', depth: 2, role: 'system' })
  expect((extras.characterBook as { entries: { keys: string[]; order: number }[] }).entries[0]).toMatchObject({ keys: ['猫'], order: 5, content: '她养过一只猫' })
})
