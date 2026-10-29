# 停止时的验证证据

2026-10-29。仅整理已有结果，停止后没有重新构建或测试。

| 检查 | 已有结果 | 覆盖边界 |
|---|---|---|
| companion JVM | 14 套件/104 项，0 失败/错误/跳过 | 最近主线定向检查，早于停止检查点最后合并/根接线 |
| Android Kotlin | 生产 Kotlin 与 instrumentation 源码编译成功 | 仪器测试没有设备执行，不是 APK/真机结果 |
| React | Device 4、Social 5、Presence 6、Life 10、GameSession 2，共 27 项 | 最后 C 新测试及部分根改动之后未重跑 |
| TypeScript / companion ESLint | 成功、日志为空 | 不是全仓 lint 或最终 Vite/Capacitor 打包 |

最后 Kotlin 日志为 BUILD SUCCESSFUL in 1m 4s，曾有 daemon 启动重试但最终成功。已有 XML/日志计数及 SHA-256 见 [existing-results.json](existing-results.json)，只用于定位当时证据，不表示当前源码通过。原始日志未公开上传。

A 的 Android SQLite 测试仅编译；B/C 独立检查边界见模块文档，不与主线数字相加。最终 A/B/C 与根接线未统一编译。历史浏览器场景不是原生体验，历史 release 包不是本轮产物。

本轮没有新 APK、最终包结构/签名检查、Android 16 真机、真实中转、QQ/B站动作或长期服务运行结果。
