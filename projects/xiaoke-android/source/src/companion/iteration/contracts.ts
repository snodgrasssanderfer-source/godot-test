export type ModuleDomain = 'life' | 'device' | 'social'
export interface ModulePanelProps {
  state: Record<string, unknown>
  run: (action: string, data?: Record<string, unknown>) => Promise<unknown>
  available: boolean
}
export interface ModuleSnapshot {
  life: Record<string, unknown>
  device: Record<string, unknown>
  social: Record<string, unknown>
}
export interface ModelEvent {
  requestId: string
  type: 'queued' | 'delta' | 'completed' | 'failed' | 'cancelled'
  text?: string
  error?: string
  usage?: Record<string, unknown>
}
