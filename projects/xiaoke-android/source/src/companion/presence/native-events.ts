import type { PresenceState } from './contract'

export interface PresenceEventSource {
  addListener: (eventName: 'presenceState', listener: (event: PresenceState) => void) => Promise<{ remove: () => Promise<void> }>
}

/**
 * One event subscription to the host's controller. No polling or companion execution is introduced.
 * Cleanup handles a native listener that resolves after React has already unmounted.
 */
export function subscribePresence(source: PresenceEventSource, onState: (state: PresenceState) => void, onError: (error: unknown) => void): () => void {
  let disposed = false
  let remove: (() => Promise<void>) | undefined
  void source.addListener('presenceState', state => {
    if (disposed) return
    const phases = ['idle', 'observing', 'listening', 'thinking', 'speaking', 'paused', 'error']
    if (!state || typeof state.running !== 'boolean' || typeof state.displayName !== 'string' || !phases.includes(state.phase) || !['companion', 'focus', 'silent'].includes(state.mode)) {
      onError(new Error('原生陪伴状态格式不符合接口合同。'))
      return
    }
    onState(state)
  }).then(handle => {
    if (disposed) return handle.remove()
    remove = () => handle.remove()
  }).catch(error => { if (!disposed) onError(error) })
  return () => {
    if (disposed) return
    disposed = true
    if (remove) void remove().catch(onError)
  }
}
