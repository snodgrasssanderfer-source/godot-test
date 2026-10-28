import type { Persona } from './bridge'

// Character Card V2/V3 data interoperability; no scripts, remote assets or instructions execute on import.
export async function readCharacterCard(file: File): Promise<Omit<Persona, 'version'>> {
  if (file.size > 8 * 1024 * 1024) throw new Error('角色文件不能超过 8 MB')
  let json = ''
  if (file.type === 'image/png' || file.name.toLowerCase().endsWith('.png')) {
    const bytes = new Uint8Array(await file.arrayBuffer())
    const view = new DataView(bytes.buffer)
    if (bytes.length < 20 || view.getUint32(0) !== 0x89504e47 || view.getUint32(4) !== 0x0d0a1a0a) throw new Error('不是有效的 PNG 文件')
    const cards = new Map<string, string>()
    for (let pos = 8; pos + 12 <= bytes.length;) {
      const size = view.getUint32(pos)
      if (size > bytes.length - pos - 12) throw new Error('PNG 数据不完整')
      const type = new TextDecoder().decode(bytes.subarray(pos + 4, pos + 8))
      if (type === 'tEXt') {
        const chunk = bytes.subarray(pos + 8, pos + 8 + size), split = chunk.indexOf(0)
        if (split > 0) {
          const name = new TextDecoder().decode(chunk.subarray(0, split))
          if (name === 'chara' || name === 'ccv3') {
            const base64 = new TextDecoder().decode(chunk.subarray(split + 1))
            cards.set(name, new TextDecoder().decode(Uint8Array.from(atob(base64), c => c.charCodeAt(0))))
          }
        }
      }
      pos += size + 12
      if (type === 'IEND') break
    }
    json = cards.get('ccv3') ?? cards.get('chara') ?? ''
    if (!json) throw new Error('PNG 没有 Character Card V2/V3 文本资料；普通图片请保留为外观参考')
  } else json = await file.text()
  const value: unknown = JSON.parse(json)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('角色文件格式无效')
  const card = value as Record<string, unknown>
  const data = card.data && typeof card.data === 'object' ? card.data as Record<string, unknown> : card
  const sections: [string, string][] = [
    ['description', '角色描述'], ['personality', '性格资料'], ['scenario', '情境'], ['mes_example', '对话示例'], ['creator_notes', '创作者说明'],
  ]
  const text = typeof data.text === 'string' ? data.text : sections.filter(([key]) => typeof data[key] === 'string' && data[key]).map(([key, title]) => `${title}\n${data[key]}`).join('\n\n')
  if (typeof data.name !== 'string' || !data.name.trim() || data.name.length > 40 || text.length > 64000) throw new Error('名称需为 1–40 字，正文不能超过 64000 字')
  return {name: data.name, text, source: `主人导入：${file.name.slice(0, 120)}；请自行确认来源与使用许可`}
}

export function downloadText(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], {type: 'application/json;charset=utf-8'}))
  const link = document.createElement('a'); link.href = url; link.download = name; link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
