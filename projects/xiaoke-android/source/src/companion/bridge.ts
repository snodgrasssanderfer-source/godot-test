import { Capacitor, registerPlugin } from '@capacitor/core'

export interface Profile {
  id: string; model: string; thinking: 'low' | 'medium' | 'high'; thinkingMode: 'level' | 'none'
  maxOutputTokens: number; contextBudget: number; maxHops: number; timeoutSeconds: number
  images: boolean; video: boolean; audio: boolean; pdf: boolean; tools: boolean
}
export interface Settings {
  endpoint: string; activeModel: string; profiles: Profile[]; networkEnabled: boolean
  backgroundEnabled: boolean; qqEnabled?: boolean; paused: boolean; dailyCalls: number
  ownerQqId: string; displayName: string
}
export interface Entry { id: string; scope: string; [field: string]: unknown }
export interface Persona { name: string; text: string; version: number; source?: string }
export interface Snapshot {
  settings: Settings; keyConfigured: boolean; persona: Persona; personaVersions: Entry[]
  memories: Entry[]; conversations: Entry[]; tasks: Entry[]; messages: Entry[]; observations: Entry[]
  permissions: { accessibility: { serviceStatus: string; allowedPackages: string[] }; notificationAccess: boolean; overlay: boolean; androidVersion: string; abis: string[] }
}
interface Bridge { state(): Promise<Snapshot>; command(options: {action: string; data: Record<string, unknown>}): Promise<{result: unknown}> }
export const nativeCompanion = Capacitor.isNativePlatform()
export const companion = registerPlugin<Bridge>('Companion', { web: () => import('./browser').then(m => new m.CompanionBrowser()) })
export const ownerScope = 'owner:local'
export const field = (record: Entry, key: string) => typeof record[key] === 'string' ? record[key] as string : ''
export async function command(action: string, data: object = {}) { return (await companion.command({ action, data: {...data} })).result }
export const profile = (model: string): Profile => ({ id: model, model, thinking: 'low', thinkingMode: 'level', maxOutputTokens: 32768, contextBudget: 65536, maxHops: 4, timeoutSeconds: 180, images: true, video: true, audio: true, pdf: true, tools: true })
export const defaultSettings = (): Settings => ({ endpoint: 'https://api.apikey.fan/v1beta', activeModel: 'gemini-3.8-flash', profiles: [profile('gemini-3.8-flash'), profile('gemini-4-argon')], networkEnabled: false, backgroundEnabled: false, qqEnabled: false, paused: false, dailyCalls: 100, ownerQqId: '', displayName: '小克' })
