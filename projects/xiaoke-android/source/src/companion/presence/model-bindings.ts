import { EMOTION_VRMExpressionName_value } from './airi/emotions'
import { WLIP_SYNC_VOWELS } from './airi/vowel-driver'
import type { CharacterFrame } from './presentation'

const VRM_EXPRESSION_BY_VOWEL = { A: 'aa', E: 'ee', I: 'ih', O: 'oh', U: 'ou' } as const

/** The host passes an already loaded, licensed VRM's expression manager. */
export interface VrmExpressionPort {
  setValue: (name: string, value: number) => void
}

/**
 * Adapted from AIRI vrm/lip-sync.ts. Call after animation and before vrm.update(delta).
 * The host remains responsible for loading, model disposal, blinking, and GPU rendering.
 * This binding owns only AEIOU and the AIRI emotion preset names present in the model.
 */
export function createVrmBinding(manager: VrmExpressionPort, available: ReadonlySet<string>) {
  const emotions = new Set(Object.values(EMOTION_VRMExpressionName_value).filter((name): name is string => Boolean(name)))
  return (frame: CharacterFrame): void => {
    for (const vowel of WLIP_SYNC_VOWELS) {
      const name = VRM_EXPRESSION_BY_VOWEL[vowel]
      if (available.has(name)) manager.setValue(name, frame.vowels[vowel])
    }
    const selected = EMOTION_VRMExpressionName_value[frame.emotion]
    for (const name of emotions) {
      if (available.has(name)) manager.setValue(name, name === selected ? frame.intensity : 0)
    }
  }
}

export interface Live2DParameterPort {
  setParameterValueById: (name: string, value: number) => void
}

/** Bind only parameters confirmed in the user's selected model. */
export interface Live2DParameterNames {
  mouthOpen: string
  eyeX?: string
  eyeY?: string
}

/**
 * Apply after model motion/expression updates so speech owns the final mouth value.
 * This does not load Cubism, execute motion files, or claim every model uses standard parameter names.
 * Expressions and blinking remain with the model's existing controller.
 */
export function createLive2DBinding(model: Live2DParameterPort, names: Live2DParameterNames) {
  return (frame: CharacterFrame): void => {
    model.setParameterValueById(names.mouthOpen, frame.mouthOpen)
    if (names.eyeX) model.setParameterValueById(names.eyeX, frame.gazeX)
    if (names.eyeY) model.setParameterValueById(names.eyeY, frame.gazeY)
  }
}
