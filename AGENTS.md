# AGENTS.md — 开发守则

> 适用于所有开发者与 AI 编码代理。默认分支 `main`，远程 `origin`（GitHub）。

## 核心规范

1. **所有更改必须通过 GitHub PR 合入 `main`**。
   禁止本地合并分支后 push、禁止直接 `git push origin main`、禁止任何绕过 PR 的合入。
2. **合入只能由 `gh pr merge` 执行，且必须使用压缩合并（squash）**。
   禁止本地 `git merge`/`git rebase` 合入 PR 分支；禁止在 GitHub Web 使用普通/Rebase merge。
3. **更改拆分为最小功能的 commit**；相关功能的 commit 汇总为一个 PR，squash 成一条提交落到 `main`。
   **PR 标题 = 压缩后的提交信息**，符合 Conventional Commits。

例外：仓库初始化（首次提交工程骨架）允许直接推 `main`。

## 标准流程

```text
同步 main → 建分支 → 最小 commit 逐步提交 → push 分支 → gh pr create → gh pr merge --squash
```

```bash
git checkout main && git pull origin main
git checkout -b <type>/<short-description>

# 每次只提交一个最小功能点
git commit -m "<type>: <简短描述>"
git push -u origin <branch>

gh pr create --base main --head <branch> --title "<type>: <简短描述>" --body "<变更说明>"

# 唯一合法的合入方式
gh pr merge <branch> --squash --delete-branch

# 合入后同步
git checkout main && git pull origin main && git branch -D <branch>
```

CI 失败或 review 需修改时：在**同一分支**补最小 commit 并 push，不要另开分支。

## 提交规范（Conventional Commits）

格式：`<type>(<scope>)?: <简短描述>`

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 重构（不改行为） |
| `docs` | 文档 |
| `chore` | 构建/依赖/配置/杂务 |
| `test` | 测试 |
| `perf` | 性能优化 |
| `style` | 格式（不改行为） |

- 一个 commit 只做一件事；描述用祈使句、小写开头，≤72 字符。
- 禁止 `update files` / `wip` 等无意义提交。

## 仓库结构规范

```
any-listen-android/
├─ .github/workflows/android.yml   CI：单测 + 出 debug/release APK
├─ app/                            唯一应用模块
│  └─ src/main/java/dev/waadri/anylisten/
│     ├─ data/config/              服务器 URL、密码等配置（DataStore）
│     ├─ data/remote/              鉴权、message2call 编解码、WebSocket 会话
│     ├─ domain/                   仓库与领域模型
│     ├─ playback/                 Media3 ExoPlayer 与 MediaSessionService
│     └─ ui/                       Compose 界面
├─ app/src/test/                   纯 JVM 单元测试（协议层必须覆盖）
└─ gradle/libs.versions.toml       依赖版本唯一真源
```

目录规则：

- 网络协议相关代码只放 `data/remote/`，不放 `ui/`。
- UI 只依赖 `domain/` 暴露的状态，禁止在 Composable 里直接调 RPC。
- 新增依赖必须写进 `gradle/libs.versions.toml`，禁止在模块里硬编码版本号。

## Kotlin / Android 规范

- **新增代码一律 Kotlin**，禁止 Java。新增界面一律 Compose，禁止 XML 布局。
- **协议层（`data/remote/`）的编解码必须是纯函数**，不得持有 Android 依赖，保证可在 `app/src/test/` 用纯 JVM 单测覆盖。
- **禁止 `!!` 强解包**；可空值必须显式处理。
- **数据模型必须显式声明字段**（`@Serializable` data class），禁止用 `Map<String, Any>` 之类的动态取值承载协议数据。
- 网络与磁盘操作禁止在主线程；协程作用域必须与生命周期绑定。

## 禁止事项

- ❌ 本地合并分支或直推 `main`；❌ 用 `gh pr merge` 之外的方式合入 PR。
- ❌ 一个 commit / 一个 PR 混入多项无关更改。
- ❌ 提交密钥、签名文件、`local.properties`、构建产物（见 `.gitignore`）。
- ❌ 修改 any-listen 服务端代码——本项目是纯客户端，服务端行为差异应通过兼容层解决。
- ❌ 文档类更改也**必须**走 PR 流程，不允许直接提交 `main`。

## 适用对象

- 本守则适用于所有人工与代理提交；冲突时以本文件为准。
- 本文件自身的更改同样必须通过 PR 流程合入。
