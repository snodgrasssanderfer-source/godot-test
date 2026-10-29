# B 安卓音频、悬浮球与输入控制

固定基线：`34290cab4eac3b75caf92c7c89711bf5727d302b`。独立分支：`companion/devicev2-20261029`。本文件描述实际接口；真机效果仍需后续验证。

## 主集成接线

1. 按进程唯一创建 `devicev2.DeviceModule(context, ports)`，注册 `domain="device"`。不要每次 snapshot 创建；宿主生命周期结束时调用 close。
2. 已有 `command('module',{domain:'device',action,data})` 路由到 execute。所有命令和 snapshot 仅接受宿主构造的 `owner=true,channel="owner_ui",scope="owner:local"`。不读取 JSON 中的 owner。
3. 根界面挂 `DevicePanel`，传 `ModulePanelProps`。例如：

```tsx
<DevicePanel state={snapshot.modules.device}
  available={nativeCompanion}
  run={(action, data = {}) => command('module', {domain: 'device', action, data})} />
```

4. 应用 `integration/device-manifest.patch`，注册 `DeviceConsentActivity`、`DeviceAudioService` 与 `FOREGROUND_SERVICE_MEDIA_PROJECTION`。基线已有 RECORD_AUDIO、MICROPHONE FGS 权限。没有新增依赖，不需要根 Activity 转发结果。
5. `control.stop` 必须可独立于占用动作线程的请求执行，不能排在长任务后面。它不等待主线程、不取消陪聊或模型任务。浮球和通知直接进入同一输入安全入口。
6. 消费 `moduleState` 时重新 snapshot；`control.stopped`、`action.completed` 携带 scope/sourceId/data，不含原始图像或 PCM。宿主 emit 应非阻塞。
7. snapshot 的 `capture.preview` 是本机短期内存画面。不要写入日志、数据库、同步或备份。
8. 接入 `integration/device-context.patch` 的三处钩子：用 A 的 `contextSnapshot/current epoch/commitContext(epoch,sourceRefs)` 安装 `presence.PresenceContexts.access`；将 `life.contentChanged` 在 UI 通知前交给 `device.onEvent`；宿主音频请求检查 `contextEpoch`。补丁依据主线 `fe93ac83599633a5fc04a2b51104f879071a0914`，没有直接修改主线文件。A/根接线完成前，陪伴仍能交流，但不读取旧记忆、不开放事件保存，不伪造已保存。

## 入口和载荷

| 动作 | 载荷与结果 |
| --- | --- |
| overlay.visibility | `{visible:boolean}`；显示或隐藏浮球，保留陪聊/收音。异步服务创建返回 requested，最终显示状态由 snapshot/事件反映。 |
| capture.request | `{packageName}`；获得十分钟单应用观察范围，打开该应用，异步取一帧。返回 `{jobId,state:"queued"}`；实际结果在 capture。需要用户已授予悬浮权限和开启无障碍。 |
| capture.stop / status | `{}`；stop 撤销设备模块自己的观察与临时画面，不结束另一路原 presence 会话。 |
| control.start / resume | `{packageName,singlePlayer:true,durationSeconds:120,expectedInputEpoch}`；要求本机主人、现有 DSH 应用控制白名单及宿主 phone_input gate。有效期 10–300 秒。返回新 sessionId/inputEpoch/expiresAtMs。 |
| control.stop / status | `{}`；stop 撤销 lease、提高 epoch、拒绝旧动作。重复停止不会重新授权，也不停止收音/观察/陪聊。 |
| control.tap | `{requestId,sessionId,inputEpoch,frameId,x,y}`，屏幕原生像素坐标。 |
| control.longPress | tap 载荷及 `durationMs`，500–1500ms。 |
| control.swipe | tap 载荷及 `toX,toY,durationMs`，100–1500ms。 |
| audio.configure | `{microphone:true,playback:true,durationSeconds:20,packageName}`；1–30 秒，两路至少选一路，内播限制到指定应用 UID。配置持久保存，原始声音不落盘。 |
| audio.start | `{}`；要求助手在前台，通过自有 Activity 按次申请录音/投影。返回 awaiting_permission；实际 AudioRecord 启动后才变 recording。 |
| audio.stop | `{captureSession?}`；带 session 时拒绝误停新会话；等待权限中的请求也能取消。 |
| audio.read | `{captureSession,source:"mic"|"playback"}`；结束录音后返回 `{captureSession,source,mime:"audio/wav",base64}`。不上传。 |
| audio.analyze | `{captureSession,prompt}`；主人主动将实际采到的片段交 HostPorts.requestModel，返回 jobId，文本/错误在 audio.analysis。 |
| audio.cancelAnalysis | `{}`；只取消宿主模型分析，旧回调被代际隔离。客户端取消不保证服务端停止计费。 |
| speech.interrupt | `{}`；复用原 presence interrupt，取消当前语音回应。与输入停止互不相连。 |

主人确认恢复示例（epoch 必须来自当前界面 snapshot；此前停止会使旧确认失效）：

```json
{"domain":"device","action":"control.resume","data":{"packageName":"example.game","singlePlayer":true,"durationSeconds":120,"expectedInputEpoch":4}}
```

单次动作示例：

```json
{"domain":"device","action":"control.tap","data":{"requestId":"unique-gesture-1","sessionId":"current-control-session","inputEpoch":6,"frameId":"observed-frame-id","x":540,"y":900}}
```

不能复用示例中的 session、epoch 或 frameId；它们仅说明结构。

## 宿主授权和模型约定

每次控制授权和动作均调用：

```json
{"type":"phone_input","target":"example.game","scope":"owner:local","requestId":"unique-gesture-1","controlSessionId":"current-control-session","contentDigest":"sha256-of-current-payload"}
```

start/resume 尚无控制 session，省略 controlSessionId/contentDigest。只有 allowed=true 才继续；执行器仍独立检查白名单、窗口、敏感页面、授权时效与动作代际。群消息无法形成主人确认。浮球按钮使用原先宿主提供的本机主人身份，不从消息构造身份。

音频分析通过唯一的宿主模型通道：`scope="owner:local",lane="owner",purpose="audio_understanding",prompt,parts,maxOutputTokens=1024,contextEpoch`。parts 按 mic/playback 分别附来源说明与 `inlineData{mimeType:"audio/wav",data}`。需要 HostPorts 的 completed.text 提供完整可见结果；failed/cancelled 明确回报。模块不读取密钥、不自建 Gemini/中转客户端、不绕过主人的独立额度。parts 只授权本次推理，不授权长期保存原音。完成后向 observation.summary 发带来源、contextEpoch/sourceRefs 和 kind=inference 的可见摘要。这个通知尚不是已保存经历：A 需要真实同域 observation 记录，当前 B 不设置 meaningful、不将原音自动持久化。若主集成要转为长期记忆，必须先在 `commitContext` 事务中保存摘要来源，再发同一 sourceId；不能仅见通知就声称记忆闭环完成。

### 删除与忘记传播

A 当前事件载荷为 `{contentEpoch,action}`，主线转换成 `ModuleEvent("life.contentChanged","owner:local","content:<epoch>",data)`。contentEpoch 是全局代际，所以即使删除发生在另一会话，也保守清除当前临时陪伴上下文；重复 / 更旧代际不会重复重启。

设备模块取消音频分析、清除临时 PCM / 画面，并调用 `CompanionPresenceHost.invalidateContext()`。原生陪伴立即提高本地代际，取消旧模型 / TTS / ASR、清除 recent、字幕、lastReply、截图、候选及缓存音频；在仍有效的会话期限内重建输入票据，保留模式、静音与收听设置。正在听的旧识别结果不能带入新会话。这个隐私操作与 `control.stop` 没有关联；输入急停仍只停止点击输入。

每次自然陪伴模型请求取得同一份记忆快照和来源票据，并继承临时上下文的已保存祖先。即使删除事件尚未送达，数据库 epoch 已变化也会拒绝旧回应、候选保存和下一轮混用旧 recent。只有显式保存时才在同一个 `commitContext(epoch,sourceRefs)` 事务中创建 observation + memory；模型回应标为 inference。保存事件只留文字依据，原截图仍是临时数据。此处原子性依赖主线真实事务适配，不由 JVM 假存储测试替代。

## 状态

snapshot 包含 capture/audio/control/overlay，另附原 voice 状态。capture 有 frameId/captureSession/packageName/windowId/rotation/capturedAtMs/screenHash/left/top/width/height/preview。preview 是临时 JPEG；十分钟到期或 capture.stop 会清除。

control 有 state/sessionId/inputEpoch/packageName/expiresAtMs/reason/activeRequestId/releasePending。收到系统手势回执前不会宣称完成；释放不确定时维持 releasePending 并拒绝恢复，不能自动重试。重新进入应用进程会使旧授权全部失效。

audio 有 state/jobId/captureSession/detail/configuration/tracks/analysis。每个音轨有 samples、sequence、sampleRate=16000、channels=1、firstAtMs、lastAtMs、hasSignal。时间来自读取时 elapsedRealtime，不声称两路硬件时间已对齐。无信号可能是真静音或系统/目标应用限制，不伪造可捕获结论。临时 PCM 最多保留五分钟或到下次采集。

## 已实现的行为与界限

- 52dp 小球；隐藏只改变显示。球上静止五秒计时不依赖 MOVE 事件；移动越界、多指、取消、移除会取消计时。通知和面板提供立即“仅停止输入”。
- 安卓无无副作用的任意时刻 gesture cancel。本实现将手势分为不超过 80ms 的连续片段，停止后只发送最后触点的释放，不注入替代点击。系统调度延迟、注入期间用户触摸取消和真实释放行为尚待设备测试。
- 不宣称普通权限能监听任意屏幕位置的完整五秒手势。没有引入 /dev/input、Shizuku 或 root 扩权。
- 动作绑定五秒内画面，并在派发前重取图像核对像素摘要、窗口、旋转和节点。像素变化会拒绝操作，因此动画画面可能无法执行；当前偏向静态/可暂停场景。需要后续目标语义定位，不声称通用 RPG 已验证。
- 原生浮球面板能刷新游戏画面、选点、点击/长按；滑动原语供宿主调用。动作后重新观察，系统完成手势不等于游戏目标达成。自动游戏规划/连续实战尚未完成。
- 实际 AudioRecord 分轨及 MediaProjection 生命周期已接入。权限拒绝、投影撤销、录音失败、取消和限时状态都明确返回。没有摄像头或受保护媒体绕过。
- 沿用唯一 presence ASR/TTS 链。PCM 占用麦克风时挂起系统 ASR，完成后恢复原监听意图；耳机抢话仍是未验证实验，外放仍按轮流听说描述。麦克风可能包含助手外放回声，不提供身份/情绪识别保证。

## 文件所有权与来源

B 修改范围：`companion/devicev2/**`、对应原生测试、`src/companion/devicev2/**`、`CompanionOverlayService.kt`、`CompanionPresenceHost.kt`、`presence/AndroidPresenceVoice.kt`、`presence/CompanionPresenceController.kt`、本契约/证据/Manifest 补丁。未修改根 App、Plugin/Engine/Service、共享 schema、构建配置、依赖或 AIRI 渲染目录。

| 来源 | 实际模块与使用方式 | 版本与许可 |
| --- | --- | --- |
| [当前项目固定基线](https://github.com/snodgrasssanderfer-source/godot-test/tree/34290cab4eac3b75caf92c7c89711bf5727d302b/projects/xiaoke-android/source/android/app/src/main/java/io/deepseekharness/mobile/companion) | 直接修改已有浮球和 AndroidPresenceVoice/Controller；保留单语音链，修复过时 MediaPlayer prepared 回调可能停止新播放的情况。 | 34290cab，保留工程许可与已有归属。 |
| [DSH](https://github.com/dphmoblie/deepseek-harness-android/tree/e9be0e43629f8b744991c2cf9efd160ca7d41abc) | 调用既有 DeepSeekAccessibilityService、复用 OverlayBallPolicy；没有绕过敏感窗口/锁屏门控。 | v0.3.0 / e9be0e43629f8b744991c2cf9efd160ca7d41abc，MIT。 |
| [RootEncoder InternalAudioSource.kt](https://github.com/pedroSG94/RootEncoder/blob/f1bbda7ca6fb92bb599206fda2b97745ac11b7f2/encoder/src/main/java/com/pedro/encoder/input/sources/audio/InternalAudioSource.kt) | 只读核对 AudioPlaybackCapture 的平台接线；没有复制、链接或执行 RootEncoder 模块。 | 2.8.1 / f1bbda7ca6fb92bb599206fda2b97745ac11b7f2，Apache-2.0。 |
| [FloatingX FxControl.kt](https://github.com/Petterpx/FloatingX/blob/c307c380d43dd9dd652159c0e1412aa798e2caae/floatingx-core/src/main/kotlin/com/petterp/floatingx/core/FxControl.kt) | 只读评估后沿用已有浮球，未引入该库。 | 3.0.0 / c307c380d43dd9dd652159c0e1412aa798e2caae；报告标注 MIT，未作为分发依赖。 |

平台依据：[音频捕获](https://developer.android.com/media/platform/av-capture)、[MediaProjection](https://developer.android.com/media/grow/media-projection)、[前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types)、[无障碍手势 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler))。

完整命令和本轮测试边界见 `evidence/devicev2/VERIFICATION.md`。没有运行真机/模拟器、真实中转、QQ/B站账号动作，没有构建或分发 APK；主集成完成接线后构建新 APK。
