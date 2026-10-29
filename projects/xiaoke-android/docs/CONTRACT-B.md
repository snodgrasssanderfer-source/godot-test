# B 设备模块接口与进度

基线：`34290cab4eac3b75caf92c7c89711bf5727d302b`。分支：`companion/devicev2-20261029`。
实现目录：`source/android/app/src/main/java/io/deepseekharness/mobile/companion/devicev2`；界面：`source/src/companion/devicev2/DevicePanel.tsx`。

## 接线

`DeviceModule(context, ports)` 实现共享 `ModuleEndpoint`。所有授权只能由宿主创建的 `CommandPrincipal(owner=true,channel="owner_ui",scope="owner:local")` 发起。普通模型输出、群和远程会话不能自授设备控制。根插件通过既定 `command('module',{domain:'device',action,data})` 路由。`DevicePanel` 使用 `ModulePanelProps`。

新增 `DeviceConsentActivity` 负责运行时录音许可与每次 MediaProjection 同意；不要求根插件转发 Activity result。根 Manifest 需声明该 Activity（exported=false）及 `DeviceAudioService`（exported=false，microphone|mediaProjection）。具体补丁随实现交付，不修改根文件。

## 动作载荷

- `overlay.visibility {visible:boolean}`：显示/隐藏已有悬浮球；隐藏不停止陪聊或收音。
- `capture.request {packageName:string}`：创建短时单应用观察范围、打开指定应用并异步获取一帧；返回 `{jobId,state:"queued"}`，真实结果在 snapshot.capture。
- `capture.stop {}`：撤销设备模块自己的观察范围；不会结束原陪伴会话。
- `capture.status {}`：当前帧元数据。原图为短期内存，不进备份。
- `control.start` / `control.resume {packageName,singlePlayer:true,durationSeconds:120}`：要求本机主人明确确认、现有 DSH 应用白名单及宿主 `phone_input` 授权；返回新的 `{sessionId,inputEpoch,expiresAtMs}`。不接受联机或独立游戏账号方案。
- `control.stop {}`：仅停本模块输入，撤销 lease、提高 epoch；保留观察、收音、陪聊。重复停止幂等。
- `control.tap` / `control.longPress {requestId,sessionId,inputEpoch,frameId,x,y,durationMs?}`；`control.swipe` 另有 `toX,toY,durationMs`。坐标为原生屏幕像素。每个动作只允许一次，绑定新鲜画面、同应用/窗口/旋转，并逐次经过宿主授权。返回真实系统回执与后置观察；画面变化不等于游戏目标达成。
- `control.status {}`：状态、停止原因、在途动作与释放状态。
- `audio.configure {microphone:true,playback:true,durationSeconds:20,packageName}`：限时配置；内播限指定应用 UID，不采其它应用。
- `audio.start {}`：返回等待系统授权的 jobId，只有实际 AudioRecord 启动后进入 recording。
- `audio.stop {captureSession?}`：停止采集；提供 session 时不允许误停新会话。
- `audio.read {captureSession,source:"mic"|"playback"}`：返回有界 WAV/元数据，仅从内存取出。无音量数据标注静音/不可捕获未知，不假称有声音。
- `audio.analyze {captureSession,prompt}`：主人主动请求，将已采两路音频及来源标签交 HostPorts.requestModel；实际分析文本与 requestId 回写 snapshot。不存在直连 provider。
- `speech.interrupt {}`：复用原 presence interrupt，取消当前语音回应；与 control.stop 分开。

## 状态示例（字段示意，不是测试成功）

```json
{"capture":{"state":"ready","frameId":"…","captureSession":"…","packageName":"example.game","rotation":0,"capturedAtMs":123,"width":1080,"height":2400},"audio":{"state":"idle","captureSession":"","tracks":{},"mode":"bounded_capture"},"control":{"state":"stopped","inputEpoch":0,"releasePending":false,"reason":"not_authorized"},"overlay":{"running":false,"visible":false,"globalFiveSecondGesture":false}}
```

`moduleState` 事件通知根界面刷新。`control.stopped`、`action.completed`、`observation.summary` 带 sourceId；原始 PCM/截图不广播。音频 raw clip 默认只在内存保留，限时过期或下一会话清除。

## 当前进度

- 已读取正式协议并固定基线。
- 实现中：独立输入代际、五秒长按、观察动作闭环、分轨采集与控制面板。
- 尚未运行本分支代码测试或构建；未做模拟器、真机、收费模型与真实社交验证。

## 诚实边界与依赖

普通权限仅支持自有浮球/面板五秒长按。Android 手势没有无副作用的任意时刻取消 API；本实现将手势拆成短段，停止后仅允许释放已按住触点，系统调度延迟另计。不能把 gesture dispatch 成功称为游戏目标完成，也不声称通用 RPG 已验证。

复用 DSH 原生观察及 presence 音频控制器，原始上游 [DSH e9be0e4](https://github.com/dphmoblie/deepseek-harness-android/tree/e9be0e43629f8b744991c2cf9efd160ca7d41abc)，MIT。只读参考 [RootEncoder InternalAudioSource](https://github.com/pedroSG94/RootEncoder/blob/f1bbda7ca6fb92bb599206fda2b97745ac11b7f2/encoder/src/main/java/com/pedro/encoder/input/sources/audio/InternalAudioSource.kt)，Apache-2.0；不复制、打包或执行其框架。既有浮球可复用，不引入 FloatingX。无新增运行时依赖。

遵循 [Android 音频捕获](https://developer.android.com/media/platform/av-capture)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) 及 [MediaProjection 生命周期](https://developer.android.com/media/grow/media-projection)。现有系统 ASR/TTS 默认外放时轮流听说；实验耳机抢话未验证，不称全双工。
