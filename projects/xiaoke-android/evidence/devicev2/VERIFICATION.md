# B 验证记录 — 2026-10-29

固定基线 `34290cab4eac3b75caf92c7c89711bf5727d302b`，分支 `companion/devicev2-20261029`。这是执行器结果，不能替代 Android 16 真机验收。

## 实际通过

- 原生 `:app:compileDebugKotlin :app:testDebugUnitTest`：1495 项，0 失败、0 错误、3 跳过；包含当时的 B 实现与既有项目回归。此后新增手势分段断言并修改音频摘要事件，再次执行下面的定向检查。
- 最终定向 `:app:compileDebugKotlin :app:testDebugUnitTest --tests 'io.deepseekharness.mobile.companion.devicev2.*' --tests 'io.deepseekharness.mobile.companion.presence.*' :app:lintDebug`：2026-10-29 11:31 UTC，14 项，0 失败、0 错误、0 跳过；`BUILD SUCCESSFUL in 1m 12s`。包括 7 项 InputFence、3 项 PcmSession、4 项既有 presence 测试入口。
- 前端 `pnpm exec vitest run src/companion/devicev2/DevicePanel.test.tsx`：4 项通过。`pnpm exec tsc -b` 和对 `src/companion/devicev2` 的 ESLint 检查通过。
- `git apply --check projects/xiaoke-android/integration/device-manifest.patch`：基线 Manifest 上通过；主集成负责最终合并与打包。

原生使用官方构建清单固定的 Gradle 8.11.1、JDK 21.0.13+9、Android 35、Build Tools 35、NDK 29 beta4。下载校验按工程已有 build-materials 清单进行；工具链、缓存、local.properties 没有提交。

可重跑命令（在 `projects/xiaoke-android/source`，自行配置已批准工具链路径）：

```sh
gradle -p android --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests 'io.deepseekharness.mobile.companion.devicev2.*' \
  --tests 'io.deepseekharness.mobile.companion.presence.*' :app:lintDebug
pnpm exec vitest run src/companion/devicev2/DevicePanel.test.tsx
pnpm exec tsc -b
pnpm exec eslint src/companion/devicev2
```

## 覆盖范围

InputFence 用可控时钟与闩锁验证停止 / 派发竞态、旧会话拒绝、重复请求内容绑定、待批准的恢复被停止作废、系统释放未知时禁止恢复、每段手势时长上限，以及五秒静止 / 移动 / 多指 / 取消计时。PcmSession 验证分轨数据、WAV 头、容量 / 到期、旧会话和迟到数据拒绝。React 检查等待截图时急停仍可用、权限失败不继续开启音频、关闭浮球与语音打断分离、浏览器没有伪造原生成功。

这些是生产代码的编译、主机单测与静态检查。没有执行真机 / 模拟器 / 仪器测试，没有真实模型、QQ、B站调用，没有安装到手机，没有构建或分发 APK。

## 主集成后真机待验

1. v2520A Android 16：录音拒绝、投影取消 / 撤销、目标应用禁止音频捕获、前后台切换、两个录音来源样本与可能外放回声。
2. 游戏目标白名单、窗口切换、旋转、敏感界面、重复点击；手势每段系统回调、释放失败状态与急停后的实际手指释放。
3. 球上五秒静止长按、移动 / 多指取消；输入停止后陪聊、观察和收音继续；恢复必须重新由主人确认。
4. 接入 Manifest、宿主模型 / 预算与 React 根界面后构建新 APK。不能用本地 JVM 通过证明通用 RPG、全双工或任意屏幕五秒手势可用。
