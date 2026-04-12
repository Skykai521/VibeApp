# Community Template Marketplace 方案调研

> 日期：2026-04-11  
> 对应 roadmap：Phase 3 — Community template marketplace

## 1. 目标与约束

我们希望让用户能够：

- 浏览社区分享的高质量工具模板
- 一键导入到本地项目工作区继续生成 / 修改 / 构建
- 不引入自建服务器成本
- 尽量不要求用户登录 GitHub
- 运营和审核成本保持可控

结合当前实现，模板初始化入口在 `ProjectInitializer`，本质上是把模板内容复制到 `files/projects/{projectId}/app` 下继续工作。因此，市场方案最好也输出同一种“可复制的模板包”，而不是把社区帖子正文直接当成模板源。

## 2. 先说结论

**最简单可行的方案不是直接用 GitHub Discussions 做市场后端，而是：**

1. **用 GitHub 仓库托管模板索引和模板包**
2. **App 只读取公开静态索引并下载模板包**
3. **GitHub Discussions 只作为投稿、反馈、评论和投票入口**

也就是：

**运行时数据面：静态 JSON + 模板 ZIP**  
**社区交互面：GitHub Discussions / PR**

这是当前约束下最稳、最省事、最接近零后端的方案。

## 3. 为什么不建议“直接用 Discussions 当市场”

`GitHub Discussions` 可以做社区入口，但**不适合直接作为 App 内模板市场的数据源**。

### 3.1 API 接入不合适

GitHub 官方文档说明，Discussions 主要通过 **GraphQL API** 暴露，且该 API **要求认证**。  
这意味着如果 App 直接读取 Discussion 列表、正文、评论，你基本就要在 App 里接 GitHub 登录。

这和本项目当前的目标冲突：

- 用户只是想找模板，不应该先绑定 GitHub
- OAuth / token 管理会显著增加实现复杂度
- Android 端还要处理登录状态、失效、速率限制、权限说明

### 3.2 数据结构太弱

Discussion 天然是帖子，不是包管理索引。它缺少运行时真正需要的稳定字段：

- 模板唯一 ID
- 版本号
- 最低 VibeApp 版本
- 模板 ZIP 下载地址
- SHA-256 校验值
- 预览图
- 分类 / 标签 / 能力声明
- 审核状态 / 下架状态

这些字段可以“写进正文 YAML frontmatter”，但那会把客户端变成一个帖子解析器，脆弱且难维护。

### 3.3 版本与审核流程不清晰

如果用 Discussion 直接承载模板：

- 模板作者改正文就可能改变“线上安装结果”
- 历史版本不清晰
- 下架和替换包不够干净
- 很难保证“用户安装到的就是审核过的那一版”

而模板市场真正需要的是：**可冻结、可校验、可回滚的发布物**。

## 4. 备选方案对比

## 方案 A：Discussions 既做投稿，也做运行时数据源

### 做法

- 社区作者在 Discussion 发帖
- App 读取 Discussion 列表并解析正文
- 模板文件通过正文链接、附件或外链提供

### 优点

- 社区感最强
- 几乎不需要维护额外仓库

### 缺点

- 读取依赖认证 GraphQL
- 数据结构不稳定
- 客户端解析复杂
- 版本与审核边界模糊
- 不适合长期扩展

### 结论

**不推荐。**

---

## 方案 B：公开 GitHub 仓库直接做市场，社区通过 PR 投稿

### 做法

- 建一个公开仓库，例如 `VibeApp-Templates`
- 仓库里维护 `index.json`
- 每个模板一个目录或一个 ZIP
- 社区作者直接提 PR
- App 读取静态索引并下载模板

### 优点

- 运行时完全不需要登录 GitHub
- 数据结构清晰
- 审核边界明确
- 版本和回滚简单
- 完全没有自建服务器

### 缺点

- 普通用户投稿门槛偏高
- 社区讨论氛围弱一些

### 结论

**技术上可行，而且已经足够能做 MVP。**

---

## 方案 C：公开 GitHub 仓库做市场，Discussions 做投稿入口

### 做法

- 公开仓库保存 `index.json` 和模板包
- App 只读仓库静态内容
- 用户通过 Discussion 发“模板投稿”
- 维护者审核后，把模板合入市场仓库

### 优点

- 兼顾“零后端”和“社区参与”
- App 端仍然是匿名只读，最简单
- 投稿对普通用户更友好
- 维护者有明确审核闸门

### 缺点

- 维护者需要把 Discussion 投稿转成仓库内容
- 比纯 PR 流程多一步人工整理

### 结论

**这是最推荐的方案。**

## 5. 推荐架构

## 5.1 总体设计

建议拆成两个面：

### A. 运行时分发面

使用一个公开 GitHub 仓库，例如：

- `Skykai521/VibeApp-Templates`

仓库内容：

```text
index.json
templates/
  json-viewer/
    1.0.0/
      manifest.json
      preview.png
      template.zip
  csv-inspector/
    1.0.0/
      manifest.json
      preview.png
      template.zip
```

或者把 `template.zip` 放在 GitHub Release 里，`manifest.json` 里只保留下载链接和 hash。

### B. 社区互动面

使用当前主仓库或市场仓库的 GitHub Discussions：

- `Template Submissions`
- `Template Requests`
- `Template Feedback`

用户在这里发帖、评论、点赞、补充截图。  
维护者审核通过后，把模板写入市场仓库。

## 5.2 为什么运行时优先用“静态索引”

这和现有代码很契合：

- `ProjectInitializer` 已经是“拷贝模板到工作区”的模型
- `PatternLibrary` 已经在用“JSON 索引 + 按需加载资源”的模式

所以社区模板市场只是在这个思路上把数据源从“内置 assets”扩展到“远端静态仓库”。

## 6. 推荐的数据格式

## 6.1 顶层索引 `index.json`

```json
{
  "version": 1,
  "generated": "2026-04-11T00:00:00Z",
  "templates": [
    {
      "id": "json-viewer",
      "name": "JSON Viewer",
      "summary": "Load, inspect, and pretty-print local or pasted JSON",
      "author": "Skykai521",
      "version": "1.0.0",
      "categories": ["utility", "data"],
      "tags": ["json", "viewer", "tool"],
      "minAppVersion": 100,
      "minSdk": 29,
      "targetSdk": 36,
      "templateType": "starter",
      "manifestUrl": "https://.../templates/json-viewer/1.0.0/manifest.json",
      "packageUrl": "https://.../templates/json-viewer/1.0.0/template.zip",
      "sha256": "..."
    }
  ]
}
```

## 6.2 模板清单 `manifest.json`

```json
{
  "id": "json-viewer",
  "version": "1.0.0",
  "entryTemplate": "EmptyActivity",
  "title": "JSON Viewer",
  "description": "A lightweight utility app for viewing JSON data.",
  "capabilities": ["local-storage"],
  "previewImages": ["preview.png"],
  "applyMode": "replace_workspace",
  "packageNameStrategy": "rewrite_on_import"
}
```

## 6.3 模板包 `template.zip`

建议第一版只支持一种最简单格式：

- ZIP 内就是一个已经整理好的 `app/` 工作区模板
- 导入后直接解压到临时目录
- 再走和 `ProjectInitializer` 类似的包名/项目名重写逻辑

这样可以最大化复用现有工程路径，不需要额外设计 DSL。

## 7. 远端托管方式怎么选

## 7.1 最简单的 MVP

**一个公开仓库 + 直接托管文件**

- `index.json`
- `manifest.json`
- `preview.png`
- 小型 `template.zip`

适合早期模板数量少、包也不大时使用。

## 7.2 稍微稳一点的做法

**索引用 GitHub Pages，模板包用 GitHub Releases**

推荐原因：

- `index.json` 走 Pages，URL 稳定，适合客户端轮询
- 模板 ZIP 走 Releases，天然更像“可发布产物”
- 回滚、替换、版本冻结更清晰

我更推荐这个组合作为 Phase 3 的正式方案。

## 8. 客户端实现建议

## 8.1 MVP 只做“只读安装”

第一版不要在 App 里做投稿、登录、评分。

只做：

1. 拉取市场索引
2. 展示模板卡片
3. 下载 ZIP
4. 校验 SHA-256
5. 解压到临时目录
6. 导入为本地项目工作区

这已经能满足“分享和复用高质量模板”的核心目标。

## 8.2 与现有代码的最小接入点

建议新增：

- `data/network/MarketplaceApi.kt`
- `data/repository/MarketplaceRepository.kt`
- `presentation/ui/home` 或独立 `presentation/ui/marketplace`
- `feature/projectinit` 中新增“从远端模板导入项目”的入口

一个可行的落点是：

- 保留现有 `EmptyActivity` 作为基础内置模板
- 社区模板导入时，把 ZIP 解到缓存目录
- 复用 `ProjectInitializer` 的重写与复制逻辑

## 8.3 缓存策略

最简单做法：

- 首页进入时请求一次 `index.json`
- 本地缓存上次成功结果
- 手动下拉刷新
- 下载后的 ZIP 按 `templateId/version` 缓存

如果后面再做优化，可以加 `ETag` / `If-None-Match`。

## 9. 审核与安全边界

模板市场不是纯 UI 素材库，而是**可执行 Android 工程模板**，所以必须保守。

第一版建议采用：

- **只上架维护者审核通过的模板**
- **客户端只安装索引里列出的模板**
- **安装前做 SHA-256 校验**
- **模板能力先限制在当前支持范围内**

建议暂时不要开放：

- 任意外链下载
- App 内直接导入未经审核的 GitHub URL
- 客户端执行模板内脚本

换句话说，第一版是**“社区投稿，官方上架”**，不是完全去中心化市场。

这比“人人随便贴个链接就能安装”更符合 VibeApp 当前阶段。

## 10. 投稿流程建议

## 10.1 用户侧

用户在 GitHub Discussions 的 `Template Submissions` 分类发帖，填写固定模板：

- 模板名称
- 模板用途
- 截图
- 示例输入 / 输出
- GitHub 仓库链接或 ZIP
- 是否使用网络、存储、定时任务等能力

## 10.2 维护者侧

维护者审核：

1. 是否符合 VibeApp 的生成/构建约束
2. 是否足够通用
3. 是否存在明显风险代码
4. 是否能在本地模板结构中稳定导入

审核通过后：

1. 把模板转为标准包结构
2. 生成 `manifest.json`
3. 上传 ZIP
4. 更新 `index.json`

## 11. Phase 3 的最小实施范围

建议把 Phase 3 拆成两个连续里程碑。

## 里程碑 1：Curated Marketplace

目标：先把“市场机制”跑通，不急着完全开放社区写入。

范围：

- 一个公开市场仓库
- 一个静态 `index.json`
- 5~10 个官方或精选模板
- App 内只读浏览 + 安装
- Discussion 分类上线，用于收集投稿和需求

这是**最简单且能真正上线**的版本。

## 里程碑 2：Community Submission Flow

范围：

- Discussion 投稿模板
- 维护者审核与上架流程文档
- 可选：PR 直投通道给高级用户

这时才真正具备“社区模板市场”属性。

## 12. 不建议现在做的事

Phase 3 不建议一开始就做：

- App 内 GitHub OAuth 登录
- 直接读写 Discussions API
- App 内评分 / 收藏 / 评论系统
- 完全自动上架
- 任意第三方 URL 安装

这些功能都会把“零后端”方案重新拖回复杂系统。

## 13. 最终建议

如果只选一个方案，我建议：

### 推荐方案

**“GitHub 静态仓库/Pages + Release 资产分发 + Discussions 投稿”**

原因很直接：

- **没有自建服务器成本**
- **App 端可以匿名只读，接入最简单**
- **和现有 `ProjectInitializer` / JSON 索引模式天然兼容**
- **版本、审核、回滚都清晰**
- **Discussion 仍然可以承担社区氛围和投稿入口**

## 14. 结论回答

回到原问题：

### “我们可以通过 GitHub 项目里的 discussion 来做吗？”

**可以，但不建议把它直接作为模板市场的主数据源。**

更合理的定位是：

- **Discussion = 投稿、讨论、投票、反馈**
- **市场仓库 = 真实上架内容和客户端下载源**

这样是当前最简单、最可控、也最接近“零成本后端”的方案。

## 15. 参考资料

- GitHub Discussions GraphQL API（Discussions 读取/写入基于 GraphQL，且要求认证）  
  https://docs.github.com/en/graphql/guides/using-the-graphql-api-for-discussions
- GitHub GraphQL 调用需要认证  
  https://docs.github.com/en/graphql/guides/forming-calls
- GitHub Repository Contents API（公开资源可匿名读取；目录 1000 文件上限；文件大小说明）  
  https://docs.github.com/en/rest/repos/contents
- GitHub REST API 速率限制（未认证公开请求主限额 60 次/小时）  
  https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- GitHub Release Assets（公开资源可匿名下载；支持 `browser_download_url`）  
  https://docs.github.com/en/rest/releases/assets
- GitHub Pages 限制（推荐仓库/站点 1 GB，软带宽 100 GB/月）  
  https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
