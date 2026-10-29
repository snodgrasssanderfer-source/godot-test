# AIRI source notice

Upstream: https://github.com/moeru-ai/airi

Fixed commit: `71b3c178ea6a6b828821027188f6b5f7fa56b788`.

Copyright (c) 2024-PRESENT Neko Ayaka.
License: MIT. The complete license is in `airi/LICENSE`.

The following files are unchanged copies:

| Local file | Upstream path |
| --- | --- |
| `airi/vowel-driver.ts` | `packages/model-driver-lipsync/src/shared/wlipsync/vowel-driver.ts` |
| `airi/eye-motions.ts` | `packages/stage-ui-live2d/src/utils/eye-motions.ts` |
| `airi/emotions.ts` | `packages/stage-ui-live2d/src/constants/emotions.ts` |

`model-bindings.ts` adapts the vowel mapping from `packages/stage-ui-three/src/composables/vrm/lip-sync.ts`.
It removes Vue watchers and audio node creation. The host supplies audio frames and a loaded model.

`presentation.ts` follows the terminal playback policy in `packages/stage-ui/src/libs/speech/playback-speaking-state.ts`.
It adds session capabilities, playback identity, stale callback rejection, and bounded audio telemetry.

No upstream character assets, motion files, Cubism SDK, wLipSync worklet, or model runtime are included.
The placeholder drawing is original geometry. It does not establish the user's final character choice.
