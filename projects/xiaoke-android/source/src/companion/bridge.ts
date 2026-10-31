import type { PresenceState } from './presence/contract'
import type { ModuleSnapshot } from './iteration/contracts'
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
  oneBotEnabled?: boolean; oneBotUrl?: string; oneBotAccount?: string; qqAccountLabel?: string
  shareOwnerMemoryWithQq?: boolean; allowOwnerQqMute?: boolean
  onboardingCompleted?: boolean
  autostartConfirmed?: boolean; qqGroups?: string[]; groupReplyFrequency?: number
  activityEnabled?: boolean
}
export interface Entry { id: string; scope: string; [field: string]: unknown }
export interface Persona { name: string; text: string; version: number; source?: string; extras?: Record<string, unknown>; aliases?: string[] }
export interface Snapshot {
  settings: Settings; keyConfigured: boolean; persona: Persona; personaVersions: Entry[]
  memories: Entry[]; conversations: Entry[]; tasks: Entry[]; messages: Entry[]; observations: Entry[]
  presence?: PresenceState
  modules?: ModuleSnapshot
  character?: {path?: string; version?: number; layout?: 'portrait'|'sprite-4x6'}
  overlaySession?: {running: boolean; package: string; remainingSeconds: number}
  messageBridge?: {connected: boolean; status: string; tokenConfigured: boolean; account?: string; untrustedCertificate?: string}
  claude?: ClaudeSnapshot
  permissions: { accessibility: { serviceStatus: string; allowedPackages: string[] }; notificationAccess: boolean; overlay: boolean; microphone?: boolean; postNotifications?: boolean; onDeviceSpeech?: boolean; batteryExempt?: boolean; shizuku?: 'ready' | 'no_permission' | 'not_running'; androidVersion: string; abis: string[] }
}
export interface ClaudePermission { id: string; taskId: string; scope: string; tool: string; summary: string; at: number }
export interface ClaudeSettings { enabled: boolean; model: string; effort: 'low' | 'medium' | 'high' | 'xhigh' | 'max'; fullAccess?: boolean }
export interface ClaudeSnapshot { installed: string | null; settings: ClaudeSettings; permissions: ClaudePermission[]; outage: {reason: string; detail: string; until: number} | null }
interface ClaudeWindow { utilization: number; resetsAt: number }
export interface ClaudeStatus extends ClaudeSnapshot {
  runtimeReady: boolean; version: string | null; loggedIn: boolean; authMethod: string; authNote: string
  install: {phase: 'idle' | 'checking' | 'downloading' | 'verifying' | 'done' | 'failed'; done?: number; total?: number; error?: string; version?: string}
  login: {running: boolean; checking?: boolean; exit?: number | null; output?: string; url?: string | null}
  usage: {status?: string; type?: string; updatedAt?: number; fiveHour?: ClaudeWindow; sevenDay?: ClaudeWindow; sevenDayOpus?: ClaudeWindow}
  githubConfigured?: boolean
  workspace: {path: string; exists: boolean; repo?: string; branch?: string; job?: {phase: 'idle' | 'running' | 'done' | 'failed'; error?: string; detail?: string}}
}
interface Bridge {
  addListener(eventName: 'presenceState', listener: (state: PresenceState) => void): Promise<{remove(): Promise<void>}>
  addListener(eventName: 'moduleState', listener: (state: Record<string, unknown>) => void): Promise<{remove(): Promise<void>}>
  state(): Promise<Snapshot>
  command(options: {action: string; data: Record<string, unknown>}): Promise<{result: unknown}>
}
export const nativeCompanion = Capacitor.isNativePlatform()
export const companion = registerPlugin<Bridge>('Companion', { web: () => import('./browser').then(m => new m.CompanionBrowser()) })
export const ownerScope = 'owner:local'
export const field = (record: Entry, key: string) => typeof record[key] === 'string' ? record[key] : ''
export async function command(action: string, data: object = {}) { return (await companion.command({ action, data: {...data} })).result }
export const profile = (model: string): Profile => ({ id: model, model, thinking: 'low', thinkingMode: 'level', maxOutputTokens: 32768, contextBudget: 65536, maxHops: 4, timeoutSeconds: 180, images: true, video: true, audio: true, pdf: true, tools: true })
export const defaultSettings = (): Settings => ({ endpoint: 'https://api.apikey.fan/v1beta', activeModel: 'gemini-3.8-flash', profiles: [profile('gemini-3.8-flash'), profile('gemini-4-argon')], networkEnabled: false, backgroundEnabled: false, qqEnabled: false, paused: false, dailyCalls: 100, ownerQqId: '', displayName: '小克' })
export type Run = (action: string, data?: object) => Promise<unknown>
