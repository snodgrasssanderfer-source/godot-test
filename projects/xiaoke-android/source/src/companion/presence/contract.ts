export type PresencePhase = 'idle' | 'observing' | 'listening' | 'thinking' | 'speaking' | 'paused' | 'error'
export type PresenceMode = 'companion' | 'focus' | 'silent'

/** Exact public shape from the e2a21d5 collaboration contract. Extra fields are optional telemetry. */
export interface PresenceState {
  phase: PresencePhase
  displayName: string
  text?: string
  mode: PresenceMode
  running: boolean
  [key: string]: unknown
}

export interface PresencePanelProps {
  state: PresenceState
  onCommand: (action: string, data?: object) => Promise<unknown>
}

export interface PresenceConfig {
  package: string
  minutes: number
  mode: PresenceMode
  intervalSeconds: number
  voiceInput: boolean
  voiceOutput: boolean
  allowNetworkAsr?: boolean
  allowNetworkTts?: boolean
  headphoneBargeIn?: boolean
  password?: string
  responseDeadlineMs?: number
}

export const phaseLabels: Record<PresencePhase, string> = {
  idle: '等待与你交流', observing: '正在看你授权的应用', listening: '正在听你说',
  thinking: '正在想', speaking: '正在说话', paused: '陪伴已暂停', error: '需要处理一个问题',
}

export function validateConfig(config: PresenceConfig): string | undefined {
  if (config.package && !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(config.package)) return '请填写完整应用包名，例如 com.example.game。'
  if (!Number.isInteger(config.minutes) || config.minutes < 1 || config.minutes > 180) return '本次时长需为 1–180 分钟。'
  if (!Number.isInteger(config.intervalSeconds) || config.intervalSeconds < 5 || config.intervalSeconds > 120) return '观察间隔需为 5–120 秒。'
  if (!['companion', 'focus', 'silent'].includes(config.mode)) return '请选择陪伴方式。'
  return undefined
}
