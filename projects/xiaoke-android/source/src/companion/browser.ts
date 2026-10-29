import { WebPlugin } from '@capacitor/core'
import { defaultSettings, ownerScope, type Entry, type Snapshot, type Settings } from './bridge'

const string = (value: unknown, fallback = '') => typeof value === 'string' ? value : fallback
const key = 'dsh-companion-preview-v1'
const empty = (): Snapshot => ({ settings: defaultSettings(), keyConfigured: false, persona: { name: '小克', text: '', version: 0 }, personaVersions: [], memories: [], conversations: [], tasks: [], messages: [], observations: [], permissions: {accessibility: {serviceStatus: 'off', allowedPackages: []}, notificationAccess: false, overlay: false, androidVersion: '浏览器预览', abis: []} })

/** Real browser persistence for UI validation. Never impersonates Android or model execution. */
export class CompanionBrowser extends WebPlugin {
  state(): Promise<Snapshot> {
    try { const value = localStorage.getItem(key); return Promise.resolve(value ? JSON.parse(value) as Snapshot : empty()) }
    catch { return Promise.resolve(empty()) }
  }
  async command({action, data}: {action: string; data: Record<string, unknown>}) {
    const state = await this.state()
    const id = typeof data.id === 'string' ? data.id : crypto.randomUUID()
    let result: unknown = {ok: true}
    switch (action) {
      case 'settings': state.settings = {...data, networkEnabled: false, backgroundEnabled: false} as unknown as Settings; break
      case 'persona': {
        const next = {name: String(data.name), text: String(data.text), source: string(data.source, '主人编辑'), version: state.persona.version + 1}
        state.persona = next; state.personaVersions.push({...next, id, scope: ownerScope}); break
      }
      case 'restorePersona': {
        const item = state.personaVersions.find(x => x.id === id)
        if (!item) throw new Error('版本不存在')
        state.persona = {name: String(item.name), text: String(item.text), version: state.persona.version + 1}; break
      }
      case 'memory': {
        const item = {...data, id, scope: string(data.scope, ownerScope), updatedAt: Date.now(), status: 'confirmed'} as Entry
        state.memories = state.memories.filter(x => x.id !== id).concat(item); break
      }
      case 'deleteMemory': state.memories = state.memories.filter(x => !(x.id === id && x.scope === data.scope)); break
      case 'pause': state.settings.paused = data.paused === true; break
      case 'backup': result = {text: await encrypt(JSON.stringify(state), String(data.password))}; break
      case 'restoreBackup': {
        const restored = JSON.parse(await decrypt(String(data.text), String(data.password))) as Snapshot
        if (!restored.persona || !Array.isArray(restored.memories)) throw new Error('备份格式无效')
        Object.assign(state, restored, {settings: state.settings, keyConfigured: false}); break
      }
      default: throw new Error('此操作需要安卓设备；浏览器预览不会模拟模型回复、权限或发送成功')
    }
    localStorage.setItem(key, JSON.stringify(state))
    return {result}
  }
}

const encode = (bytes: Uint8Array) => btoa(Array.from(bytes, b => String.fromCharCode(b)).join(''))
const decode = (text: string) => Uint8Array.from(atob(text), c => c.charCodeAt(0))
async function derive(password: string, salt: Uint8Array) {
  if (password.length < 8 || password.length > 256) throw new Error('备份口令须为 8–256 个字符')
  const input = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey({name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 210000}, input, {name: 'AES-GCM', length: 256}, false, ['encrypt', 'decrypt'])
}
async function encrypt(text: string, password: string) {
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await derive(password, salt)
  const value = await crypto.subtle.encrypt({name: 'AES-GCM', iv}, key, new TextEncoder().encode(text))
  return JSON.stringify({format: 'dsh-companion-browser-preview', version: 1, salt: encode(salt), iv: encode(iv), data: encode(new Uint8Array(value))})
}
async function decrypt(text: string, password: string) {
  const value = JSON.parse(text) as {format: string; salt: string; iv: string; data: string}
  if (value.format !== 'dsh-companion-browser-preview') throw new Error('这里只能恢复浏览器预览备份；正式安卓备份请在安卓端恢复')
  const key = await derive(password, decode(value.salt))
  try { return new TextDecoder().decode(await crypto.subtle.decrypt({name: 'AES-GCM', iv: decode(value.iv)}, key, decode(value.data))) }
  catch { throw new Error('口令错误或文件已损坏') }
}
