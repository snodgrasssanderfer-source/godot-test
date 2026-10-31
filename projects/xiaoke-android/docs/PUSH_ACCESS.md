# 让手机里的小克自己推分支、开 PR

现状：手机工作区（`/root/workspace/godot-test`，PRoot 里的 Ubuntu）没有 GitHub 凭据，也没有 `gh`。改完只能提交在本地，等主人推。

目标：主人在 App 里填一次 token，之后 Claude 回合里 `git push` 和开 PR 直接能用；token 不落进 rootfs、不进日志、不进仓库。

路径前缀 `source/android/app/src/main/java/io/deepseekharness/mobile/companion/`。

## 1. 存 token

- 设置页 Claude 那一栏加“GitHub token”输入框（`source/src/companion/ClaudePanel.tsx`），只显示“已配置 / 未配置”和“清除”，不回显内容。
- 后端用现成的 `CompanionSecrets(context, "github")` 存（Keystore 加密，和 Gemini key 同一套）。
- 插件接口加 `claude.githubToken.set` / `claude.githubToken.clear` / 状态里带 `githubConfigured`，只接受本机主人界面调用。

## 2. 只在主人回合里注入

`claude/ClaudeTurn.kt` 起进程前（58 行，`environment().putAll(spec.environment)` 之后），`ownerTools` 为真且配置了 token 时加：

```
GH_TOKEN=<token>
GIT_CONFIG_COUNT=1
GIT_CONFIG_KEY_0=credential.https://github.com.helper
GIT_CONFIG_VALUE_0=!f() { echo username=x-access-token; echo "password=$GH_TOKEN"; }; f
```

- 用环境变量配 credential helper，不写 `~/.gitconfig` 或 `~/.git-credentials`，进程结束就没了。
- 群聊和非主人会话的回合不注入（`ownerTools` 为假的那条分支）。
- `ClaudeWorkspace.prepare()` 的 fetch 也带上同样的环境，以后仓库改成私有也能拉。

## 3. 不让 token 漏出去

- `ClaudeStream` 解析出的文本、工具输出、`XLog`、任务的 `detail`/`fallbackReason`：发出去或落盘前把 token 原文替换成 `***`。
- 加一个单元测试：输出里含 token 时被替换；非主人回合的环境里没有 `GH_TOKEN`。

## 4. 开 PR

- 首选在 rootfs 里装 `gh`（`apt install gh`，aarch64 有包），`GH_TOKEN` 环境变量它直接认，不用 `gh auth login`。可以由小克在手机上自己装，不用打进 APK。
- 没装 `gh` 时用 curl 调 `POST /repos/{owner}/{repo}/pulls`，同样读 `GH_TOKEN`。

## 5. 提示词

`CompanionEngine.kt` 约 633 行给 Claude 的代码说明里，把“没有推送凭据就说已提交在本地，请主人推送”改成：配置了 token 时，改完推到 `claude/*` 新分支并开 PR，回复里给出 PR 链接；没配置时照旧提交在本地。

## 6. 主人在 GitHub 上做一次

- token 用 Fine-grained，只授权 `godot-test`，权限 Contents: Read and write、Pull requests: Read and write。
- 给 `main` 开分支保护（Settings → Branches → Add rule → `main` → Require a pull request before merging）。这样就算哪天推错，也推不进 main，比在 App 里拦更靠得住。

## 验证

四项 Gradle 任务照常跑。真机上：填 token → 让小克在新分支改一行文档并推送 → GitHub 上出现分支和 PR → 清除 token 后再推应失败。
