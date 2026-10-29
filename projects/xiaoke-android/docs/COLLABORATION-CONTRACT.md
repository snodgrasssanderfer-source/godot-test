# Iteration contract v1 — companion first

This is an implementation contract, not a claim that the new modules already work. Use the exact published baseline commit. Work on independent branches or patches; do not merge or force-push another contributor's branch. All paths below are relative to `projects/xiaoke-android/source` unless stated otherwise.

## Shared protocol (owned by main integrator)

Kotlin types: `android/app/src/main/java/io/deepseekharness/mobile/companion/iteration/ModuleContract.kt`.
React types: `src/companion/iteration/contracts.ts`.

The native Companion plugin will expose `command('module', {domain:'life'|'device'|'social', action, data})` and include `modules:{life,device,social}` in its snapshot. Root calls `ModuleEndpoint.execute` on a worker and coalesces `moduleState` notifications into UI reloads. Modules must persist durable changes before returning. An asynchronous action returns `{jobId,state}`; a button click must never return a fabricated success.

Root constructs `CommandPrincipal` from local UI or a verified transport account. JSON/model/tool content cannot set `owner`. Channels: `owner_ui`, `owner_qq`, `friend_qq`, `group_qq`, `background`. Keep existing scope identifiers (`owner:local`, exact account/conversation IDs); do not derive identity from display names. Group context cannot mutate owner persona, rules, grants or device control.

`HostPorts.requestModel` accepts `{scope, lane:'owner'|'activity', purpose, prompt, parts?, maxOutputTokens?, taskId?}` and returns an ID immediately. Callback JSON follows `ModelEvent`: queued/delta/completed/failed/cancelled, requestId, optional text/error/usage. Root owns credentials, Gemini protocol, independent owner/activity admission, accounting, priority and cancellation. Modules must not instantiate another provider client or read credentials. Events may arrive on a worker. Never hold a DB lock across a model call.

`HostPorts.authorizeEffect` returns `{allowed,reason?,approvalId?}` for `{type,target,scope,contentDigest?,controlSessionId?,requestId?}`. This is an additional host gate, not a replacement for module target/account checks. Exact effects include phone_input, camera, social_send, public_post, public_comment, add_friend, join_group, download, like, follow, block, leave_group. User-configured product autonomy is distinct from permission to perform real account tests during development.

`ModuleEvent` examples: conversation.message, model.completed, observation.summary, action.completed, control.stopped, budget.changed, connectivity.changed. Data carries source IDs and visible evidence, never hidden reasoning. Deduplicate by sourceId plus event type. Raw frames and ordinary received media remain cache unless explicitly retained; meaningful grounded summaries may become memory automatically.

## A — life / memory / attention / budget

Own new `.../companion/life/**`, corresponding unit/instrumentation tests under `.../companion/life/**`, and existing `CompanionStore.kt`, `CompanionBackupCrypto.kt`, `MemoryRanking.kt`. Preserve all existing public methods and snapshot fields; add backward-compatible migrations. Root will reconcile legacy behavior through adapters, so do not silently change old method semantics without documenting it.

Implement `life.LifeModule(context: Context, store: CompanionStore, ports: HostPorts) : ModuleEndpoint` (`domain="life"`). Snapshot keys: memories, diary, interests, attention, budgets. Actions: memory.search/upsert/forget/delete/history; diary.list/correct/delete; interest.list/upsert/delete; attention.set/status; budget.configure/reserve/settle/release/status; backup.export/restore. Use exact action names and document payload schemas in `docs/CONTRACT-A.md` (project root docs).

Meaningful grounded memory can save autonomously; distinguish fact, inference and preference stability. New facts supersede rather than erase valid history. Forget suppresses proactive retrieval; delete removes content plus derived summaries, indices, caches and restore reintroduction via tombstones. Source-linked first-person diary, no invented experiences. Cross-scope sharing returns an explicit sanitized summary, never unrestricted raw history. Encrypted export must cover new stores, schema versions, deletions and merge rules; never export credentials or executable send grants.

Budget reserve/settle/release must be transactional and idempotent; reserve before concurrent paid calls, count in-flight holds, handle cancellation/unknown final charge, separate activity and owner funds, and stop activity rather than lower quality. Unknown provider prices cannot silently skip reservation or imply a monetary guarantee. Document required price/ceiling inputs and fail-closed activity behavior. Attention accepts structured semantic decisions; do not map casual phrases to rigid global actions.

## B — Android audio / bubble / control / game substrate

Own new `.../companion/devicev2/**`, existing `.../companion/presence/**`, `CompanionOverlayService.kt`, `CompanionPresenceHost.kt`, `CompanionDevice.kt`, `TemporaryObservations.kt`, `PublicVideoDataSource.kt`, `VideoEvidence.kt`; matching owned tests. Own optional UI `src/companion/devicev2/**`. Do not edit the main React entry, root manifests, shared Engine/Plugin/Service, build.gradle, dependencies or existing AIRI rendering directory. Supply required manifest/dependency/permission hooks as explicit integration patches.

Implement `devicev2.DeviceModule(context: Context, ports: HostPorts) : ModuleEndpoint` (`domain="device"`). Snapshot keys: capture, audio, control, overlay. Actions: overlay.visibility; audio.configure/start/stop; capture.request/stop/status; control.start/stop/resume/tap/swipe/longPress/status. If a native Activity/MediaProjection callback is required, document a concrete public method and required forwarding in `docs/CONTRACT-B.md`. Root owns routing, not capture implementation. Optional React `DevicePanel` exports `ModulePanelProps`.

Prioritize a small bubble; hiding it must not tear down the entire conversation/session. Playback capture + microphone need explicit system consent, FGS/lifecycle cleanup, clear routing and no protected-audio bypass. Camera only through owner-directed effect authorization. Natural interruption remains a measured target; label half-duplex honestly. Retain one voice/controller loop and separate control emergency-stop from capture/chat. Five-second hold on the owned bubble/panel can be reliable; do not claim ordinary permissions observe all-screen gestures. Stop cancels queued input, fences stale generations and requires owner-approved resume. Provide bounded primitives for an authorized single-player RPG; no claim of universal game competence, no multiplayer or independent game accounts.

## C — social / approvals / durable jobs / sync

Own new `.../companion/social/**`, existing `OneBotBridge.kt`, `OneBotProtocol.kt`, `OneBotMedia.kt`, `ParticipationPolicy.kt`, `ReplyLikelihood.kt`, `QqNotificationBridge.kt`, `BilibiliReader.kt`, matching tests; `src/companion/social/**`, `deploy/napcat/**` excluding all data, and new `services/**`. Do not edit CompanionStore, Engine/Plugin/Service, Device or root entry/build configuration. Own only project `docs/CONTRACT-C.md` and related source notes.

Implement `social.SocialModule(context: Context, store: CompanionStore, ports: HostPorts) : ModuleEndpoint` (`domain="social"`). Snapshot keys: accounts, approvals, activity, scheduled, sync, service. Actions: approvals.list/approveBatch/reject; jobs.list/create/cancel/resume; activity.list; sync.configure/status/run; social.plan. Add platform-specific action names only in CONTRACT-C with exact effect/approval handling. Optional React `SocialPanel` exports `ModulePanelProps`.

Reuse independent NapCat/OneBot and stable account identity. Media types must report actual support/failure, not fake success. Friends can request bounded work without gaining owner authority. Configure low-risk autonomy separately from exact-target approvals for comments, joining, adding and downloads. Qzone daily publishing and comments remain different effects. Every approval binds account/target/content/expiry; changed payload invalidates it; unknown send result must not be blindly retried. Keep private diary separate from shareable summaries. Group output must never play as owner speech.

Implement durable local jobs, idempotent resumption, conditions/reminders and sync outbox. Phone model chat remains independent when a server is offline. Sync needs version/conflict/tombstone semantics and encryption/authentication; no plaintext memory uploaded by default. A minimal optional service is preferable to several server frameworks. No real account login, sending, liking, public server exposure or user-machine deployment during this work.

## Root integration ownership and delivery

Root owns App.tsx, CompanionApp.tsx, global navigation/styles, bridge.ts, all shared schemas/types, CompanionEngine/Plugin/Service/Secrets/Policy/Context/Routing/GeminiClient, manifests, Gradle/lockfiles, model queue and final APK. New UI is companion-first: conversation/bubble primary; life/diary/interest review and combined approval inbox accessible; agent tools secondary. Coordinate requests touching another owner's files before changing them.

Each contributor delivers: source patch or independent branch at this baseline; contract payload examples; fixed upstream URL/commit/file/license and substantive reuse; tests with actual commands/results; integration patch proposals for root-owned files; explicit unimplemented/platform/permission limitations. Do not claim mocks, compilations or unexecuted tests prove device experience. Never upload private reports, transcript, identity, credentials, signing keys, real memories, SDK/cache/rootfs or old APK. Do not run the no-KVM emulator again. New runtime dependencies need exact version/source/size/license and rationale; unfamiliar executable installation needs approval.
