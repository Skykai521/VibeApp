# 贡献指南 | Contributing Guide

感谢你对 VibeApp 的关注。

这份文档说明当前项目的开发环境、分支策略、提交规范和 PR 流程。默认目标是：所有日常开发先进入 `dev`，再由维护者定期同步到 `main`。

## 开发环境

### 必备工具

- Android Studio Ladybug (2024.2) 或更高版本
- JDK 17
- Android SDK 36（`compileSdk` / `targetSdk`）
- Android 10+ 设备或模拟器（`minSdk` 29）

### 克隆与本地构建

```bash
git clone https://github.com/Skykai521/VibeApp.git
cd VibeApp
./gradlew assembleDebug
```

推荐的额外检查：

```bash
./gradlew test
./gradlew :build-engine:test
```

## 项目架构速览

当前仓库主要由以下模块组成：

```text
app/          -> Android app，包含 presentation / feature / data / di / util
build-engine/ -> 设备端构建引擎，负责 RESOURCE -> COMPILE -> DEX -> PACKAGE -> SIGN
build-tools/  -> 随应用打包的编译工具链依赖
docs/         -> 项目文档
```

主要分层：

- `presentation`：Compose UI、导航、ViewModel
- `feature`：agent loop、project workspace、project init、icon rendering
- `data`：Room、DataStore、repository、network clients
- `build-engine`：AAPT2、JavacTool、D8、打包、签名

详细说明见 [docs/architecture.md](docs/architecture.md)。

## 分支策略

### 目标分支

- `main`：稳定分支，用于发布、打 tag 和对外默认展示
- `dev`：开发分支，所有常规功能开发和修复优先合并到这里

### 规则

1. 所有功能分支都从 `dev` 拉出。
2. 所有 PR 默认提交到 `dev`。
3. `main` 不直接接收日常 feature/fix PR，除非是维护者明确安排的紧急修复。
4. 维护者按阶段将 `dev` 同步到 `main`。

推荐理解：

- `dev` 是持续集成分支
- `main` 是阶段性发布分支

### 推荐工作流

```bash
# 首次准备
git clone https://github.com/YOUR_NAME/VibeApp.git
cd VibeApp
git remote add upstream https://github.com/Skykai521/VibeApp.git

# 同步开发分支
git fetch upstream
git checkout dev
git pull upstream dev

# 从 dev 创建工作分支
git checkout -b feature/your-feature-name
```

完成开发后：

```bash
git push origin feature/your-feature-name
```

然后在 GitHub 创建：

- base branch: `dev`
- compare branch: `feature/your-feature-name`

### `dev` 同步到 `main`

由维护者定期执行，建议以 PR 方式进行，而不是直接 push：

1. 确认 `dev` 通过 CI 和必要的手工验证
2. 创建 `dev -> main` 的发布 PR
3. 在 PR 中汇总本轮改动、风险和验证结果
4. 合并到 `main` 后再打 tag / 发布 release

## 贡献流程

### 1. 提交 Issue

- Bug 报告请使用 [Bug Report 模板](.github/ISSUE_TEMPLATE/bug_report.md)
- 功能建议请使用 [Feature Request 模板](.github/ISSUE_TEMPLATE/feature_request.md)

如果改动较大，建议先开 issue 或 discussion 对齐方向。

### 2. 创建分支

分支名建议：

- `feature/xxx`
- `fix/xxx`
- `docs/xxx`
- `refactor/xxx`
- `test/xxx`
- `chore/xxx`

### 3. 开发与验证

提交前至少确认：

- 能成功构建：`./gradlew assembleDebug`
- 修改了构建链时，优先补充 `build-engine` 相关测试
- 修改了 UI 或流程时，至少做一次对应场景的手工验证
- 文档改动和代码改动一致，不要让 README / docs 落后于实际结构

### 4. 提交代码

采用 [Conventional Commits](https://www.conventionalcommits.org/)：

| 前缀 | 说明 |
|------|------|
| `feat:` | 新功能 |
| `fix:` | Bug 修复 |
| `docs:` | 文档更新 |
| `refactor:` | 重构 |
| `test:` | 测试 |
| `chore:` | 构建、工具、依赖 |

示例：

```bash
git commit -m "feat: add build progress indicator to chat top bar"
git commit -m "fix: escape french welcome strings"
git commit -m "docs: update architecture and contribution guide"
```

### 5. 创建 PR

PR 默认目标分支：`dev`

PR 描述建议包含：

- 改动目的
- 主要实现点
- 风险点或兼容性影响
- 验证方式
- 相关 issue

如果是 UI 变更，尽量附截图或录屏。

## 评审标准

我们更关注这些点：

- 是否符合当前真实架构，而不是引入一层理想化但未落地的抽象
- 是否保持设备端构建链可用
- 是否破坏 agent / project workspace / build-engine 之间的边界
- 是否补充了必要的测试或至少提供了明确验证步骤
- 是否更新了相关文档

## 重点贡献方向

当前最有价值的方向：

- 提高生成代码的编译成功率
- 完善 agent tool 与项目工作区的协作能力
- 优化设备端构建性能和稳定性
- 改进错误日志清洗与自动修复链路
- 扩展原生 Android 模板和 UI 生成能力
- 改进项目管理、预览与导出体验

## 代码风格

- Kotlin 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Compose 代码保持状态单向流动，避免把复杂业务逻辑塞进 Composable
- 优先用接口隔离 `build-engine` 组件
- 避免使用 `GlobalScope`
- 错误处理优先返回结构化结果，而不是把异常一路外抛

## 文档维护

以下改动通常需要同步文档：

- 调整目录结构
- 修改 build pipeline 阶段
- 新增 provider / agent tool
- 修改分支策略或发布流程
- README 中的架构描述不再符合真实代码时

最少应检查：

- `README.md`
- `docs/architecture.md`
- `CONTRIBUTING.md`

## License

提交到本项目的代码默认采用与仓库一致的 [GPL-3.0 License](LICENSE)。
