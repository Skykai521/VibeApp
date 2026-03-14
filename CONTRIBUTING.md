# 贡献指南 | Contributing Guide

感谢你对 VibeApp 的关注！我们欢迎任何形式的贡献。

## 开发环境

### 必备工具

- Android Studio Ladybug (2024.2) 或更高版本
- JDK 17
- Android SDK 35（compileSdk）
- Android SDK 26（minSdk）

### 克隆与构建

```bash
git clone https://github.com/Skykai521/VibeApp.git
cd VibeApp
./gradlew assembleDebug
```

## 项目架构速览

```
app/           → 主应用（UI + Feature API + AI Agent）
build-engine/  → 编译引擎模块（AAPT2 + JavacTool + D8 + 打包/签名）
docs/          → 项目文档
```

- **UI 层**：Jetpack Compose，MVVM + UDF（单向数据流）
- **Feature API 层**：代码生成、文件操作、依赖管理、自动修复
- **编译引擎**：独立 module，可单独测试
- **AI 层**：多模型适配，Provider 模式
- **DI**：Hilt

详见 [docs/architecture.md](docs/architecture.md)

## 贡献流程

### 1. 提交 Issue

- Bug 报告请使用 [Bug Report 模板](.github/ISSUE_TEMPLATE/bug_report.md)
- 功能建议请使用 [Feature Request 模板](.github/ISSUE_TEMPLATE/feature_request.md)

### 2. 开发流程

```bash
# 1. Fork 并 clone
git clone https://github.com/YOUR_NAME/VibeApp.git

# 2. 创建分支
git checkout -b feature/your-feature-name
# 分支命名规范：feature/xxx, fix/xxx, docs/xxx, refactor/xxx

# 3. 开发并提交
git commit -m "feat: add xxx support"

# 4. 推送并创建 PR
git push origin feature/your-feature-name
```

### 3. Commit 规范

采用 [Conventional Commits](https://www.conventionalcommits.org/):

| 前缀 | 说明 |
|------|------|
| `feat:` | 新功能 |
| `fix:` | Bug 修复 |
| `docs:` | 文档更新 |
| `refactor:` | 重构 |
| `test:` | 测试 |
| `chore:` | 构建/工具链 |
| `prompt:` | Prompt 模板改进 |

### 4. PR 要求

- 清晰描述改动目的和方案
- 关联相关 Issue
- 通过 CI 检查
- 新功能需要附带基础测试

## 重点贡献方向

- 提高生成代码的编译成功率
- 支持更复杂的 UI 布局
- 优化错误修复 Prompt 的准确性
- 添加新的 App 类型模板

### ⚡ 编译链优化

`build-engine/` 模块：
- 编译速度优化
- 内存占用优化
- 支持更多 ABI
- 增量编译支持

当前真实链路顺序：

- `Aapt2ResourceCompiler`
- `JavacCompiler`
- `D8DexConverter`
- `AndroidApkBuilder`
- `DebugApkSigner`

### 📱 UI 组件扩展

扩展 AI 可以生成的 UI 组件类型：
- 新的 View 组件支持
- 更复杂的布局模式
- 动画支持

## 代码风格

- Kotlin 代码遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 ktlint 检查
- Compose UI 遵循官方最佳实践

## 联系方式

- GitHub Issues: 技术讨论
- GitHub Discussions: 想法讨论

## License

贡献的代码将采用与项目相同的 [GPL-3.0 License](LICENSE)。
