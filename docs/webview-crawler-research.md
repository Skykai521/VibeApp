# WebView 端上爬虫框架可行性调研

> 日期：2026-04-10
> 目标：评估通过应用内 WebView + HTML-to-Markdown 实现端上小型爬虫框架，替代当前频繁被反爬拦截的 OkHttp 方案

---

## 1. 当前实现分析

### 现有工具链

| 工具 | 文件 | 机制 |
|------|------|------|
| `WebSearchTool` | `feature/agent/tool/WebSearchTool.kt` | OkHttp/Ktor 请求 Bing→Baidu→Google，解析 HTML 获取搜索结果 |
| `FetchWebPageTool` | `feature/agent/tool/FetchWebPageTool.kt` | OkHttp/Ktor 抓取页面，**Readability4J** 提取正文，Jsoup 兜底 |

### 现有反爬措施
- 浏览器 User-Agent（Android Chrome Mobile）
- Sec-Fetch / Accept-Language 等标准浏览器请求头
- 15 秒超时 + 多搜索引擎降级
- 正文截断至 8000 字符

### 核心问题
即使伪装了浏览器请求头，纯 HTTP 请求仍被大量网站的反爬机制拦截，原因：
1. **无 JS 执行能力** — 无法通过 Cloudflare、WAF 的 JS Challenge
2. **无 Cookie/Session 管理** — 无法自动处理登录态和反爬 Cookie
3. **无真实浏览器指纹** — TLS 指纹、Canvas 指纹等与真实浏览器不同
4. **无法处理 SPA/动态渲染页面** — 很多现代网站内容靠 JS 渲染

---

## 2. 参考项目分析

### 2.1 saga-reader（sopaco/saga-reader）

| 属性 | 说明 |
|------|------|
| 定位 | AI 驱动的桌面阅读器，自动抓取+LLM 摘要 |
| 技术栈 | Rust + Tauri v2 + Svelte + SQLite |
| 许可证 | MIT |
| 核心思路 | **双通道抓取**：reqwest HTTP 请求 + Tauri WebView 模拟器 |

**关键架构借鉴点：**

- **WebView 模拟器**（`simulator.rs` / `scrap_host.rs`）：创建隐藏的 1920x1080 WebView，加载 URL 后等待 3 秒，通过 JS 注入 `document.documentElement.innerHTML` 获取渲染后的完整 DOM
- 串行执行（同一时刻只有一个 WebView 请求），10 秒超时
- HTML 清洗使用 `scraper` crate（CSS 选择器），去除 `<script>`/`<style>` 后提取文本
- 搜索引擎集成（百度、Bing 解析器）与我们的实现类似

**对 VibeApp 的启示：** saga-reader 的 WebView 模拟器思路完全可以移植到 Android WebView，且 Android WebView 本身就是真实的 Chrome 内核，反爬能力更强。

### 2.2 turndown（mixmark-io/turndown）

| 属性 | 说明 |
|------|------|
| 定位 | HTML → Markdown 转换 JS 库 |
| 版本 | v7.2.4 |
| 许可证 | MIT |
| 体积 | 浏览器 bundle ~30-40KB |
| 依赖 | 浏览器环境零依赖（使用原生 DOM） |

**核心 API：**
```javascript
var turndownService = new TurndownService({
  headingStyle: 'atx',
  codeBlockStyle: 'fenced',
  bulletListMarker: '-'
})
var markdown = turndownService.turndown(document.body)
```

**WebView 可行性：** 完全可行。turndown 提供浏览器 build（UMD/IIFE），在 WebView 中通过 `<script>` 加载后，调用 `evaluateJavascript()` 即可获取 Markdown 输出。

**插件生态：** `turndown-plugin-gfm` 支持表格、删除线等 GFM 扩展。

---

## 3. 可选技术方案对比

### 方案 A：WebView + Readability.js + Turndown（推荐）

```
┌─────────────┐    ┌──────────────┐    ┌────────────┐    ┌──────────┐
│  Android     │    │  页面加载完成  │    │ Readability │    │ Turndown  │
│  WebView     │───>│  JS 渲染完成  │───>│ 提取正文    │───>│ HTML→MD   │
│  (隐藏)      │    │              │    │  HTML       │    │          │
└─────────────┘    └──────────────┘    └────────────┘    └──────────┘
                                              │                │
                                        evaluateJavascript    │
                                              └────────────────┘
                                                     │
                                               Markdown 结果
                                               回调到 Kotlin
```

**组件：**
- **Android WebView**：真实 Chrome 内核，天然过反爬
- **mozilla/readability（JS）**：Firefox Reader View 同款，提取正文 HTML（去广告/导航）
- **turndown（JS）**：正文 HTML → Markdown

**优点：**
- 真实浏览器环境，能过绝大多数 JS Challenge / Cloudflare
- WebView 自带 Cookie/Session/TLS 指纹管理
- 能处理 SPA 和动态渲染页面
- JS 库体积小，打包进 assets 即可
- 已有成熟案例（saga-reader 验证了这个思路）

**缺点：**
- WebView 创建/销毁有开销（~200-500ms）
- 需要等待页面加载完成（1-5 秒）
- 串行请求（并发 WebView 耗内存）
- 需要处理 WebView 生命周期管理

**实现要点：**
```kotlin
// 1. 预加载 JS 库到 assets
// assets/js/readability.js + turndown.js

// 2. 创建隐藏 WebView
val webView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = standardChromeUA
}

// 3. 注入提取脚本
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) {
        val extractScript = """
            (function() {
                var article = new Readability(document.cloneNode(true)).parse();
                if (!article) return JSON.stringify({error: 'extraction_failed'});
                var md = new TurndownService({headingStyle:'atx'}).turndown(article.content);
                return JSON.stringify({title: article.title, content: md});
            })()
        """
        view.evaluateJavascript(extractScript) { result ->
            // result 即为 Markdown 内容
        }
    }
}
```

### 方案 B：WebView + Readability4J（保留现有 Kotlin 库）

```
WebView 加载页面 → evaluateJavascript 获取 innerHTML
    → 回传到 Kotlin → Readability4J 提取正文
    → 自写 HTML→Markdown 转换（或用 flexmark-java）
```

**优点：**
- 复用现有 Readability4J 依赖
- 逻辑在 Kotlin 侧更可控

**缺点：**
- innerHTML 回传开销大（长字符串跨 JS/Kotlin bridge）
- Readability4J 处理的是静态 HTML，某些动态属性可能丢失
- HTML→Markdown 需要额外库（flexmark-java 有 HTML→MD 模块，但较重）
- 多一次序列化/反序列化

### 方案 C：保留 OkHttp + 增加 WebView 降级

```
OkHttp 请求 → 成功？→ Readability4J 提取
    ↓ 失败
WebView 降级 → Readability.js + Turndown → Markdown
```

**优点：**
- 对简单页面保持快速路径（OkHttp < 1s）
- WebView 只在被反爬阻断时启动
- 向后兼容

**缺点：**
- 需要检测反爬失败（403/Cloudflare 页面特征判断）
- 两套代码路径增加维护成本

---

## 4. 可直接引入的开源项目

### 正文提取类

| 项目 | 语言 | Stars | 许可证 | 状态 | 适用性 |
|------|------|-------|--------|------|--------|
| [mozilla/readability](https://github.com/mozilla/readability) | JS | 9k+ | Apache-2.0 | 活跃 | **最佳选择** — WebView 内直接运行 |
| [Readability4J](https://github.com/dankito/Readability4J) | Kotlin | 173 | Apache-2.0 | 停更(2021) | 已在项目中使用，适合 OkHttp 路径 |
| [Crux](https://github.com/chimbori/crux) | Kotlin | 245 | Apache-2.0 | 活跃(v5.1) | v5 移除了正文提取，仅提取元数据 |
| [snacktory](https://github.com/karussell/snacktory) | Java | 460 | Apache-2.0 | 停更，推荐用 Crux | 不建议 |

### HTML → Markdown 转换类

| 项目 | 语言 | 许可证 | 适用性 |
|------|------|--------|--------|
| [turndown](https://github.com/mixmark-io/turndown) | JS | MIT | **最佳选择** — WebView 内运行，轻量 |
| [turndown-plugin-gfm](https://github.com/mixmark-io/turndown-plugin-gfm) | JS | MIT | GFM 表格/删除线支持 |
| [flexmark-java](https://github.com/vsch/flexmark-java) | Java | BSD-2 | 有 HTML→MD 模块，但整体较重 |
| [remark-rehype](https://github.com/remarkjs/remark-rehype) | JS | MIT | unified 生态，依赖链长 |

### 搜索引擎相关

| 项目 | 说明 |
|------|------|
| [SearXNG](https://github.com/searxng/searxng) | 元搜索引擎，可自部署，API 返回 JSON。但需要服务端 |
| 当前方案（Bing/Baidu/Google HTML 解析） | 继续使用，WebView 方案可提升搜索页面抓取成功率 |

---

## 5. 可行性评估

### 技术可行性：**高**

| 维度 | 评估 |
|------|------|
| WebView 创建 | Android 原生支持，无需额外依赖 |
| JS 注入 | `evaluateJavascript()` API 成熟稳定（API 19+） |
| 反爬绕过 | WebView = 真实 Chrome 内核，TLS/Canvas/JS 指纹与浏览器一致 |
| 内存开销 | 单个 WebView ~30-50MB，可复用实例 |
| 异步处理 | 可封装为 suspend 函数配合 Coroutine |
| JS 库打包 | readability.js + turndown.js 总计 < 100KB，放 assets |

### 风险点

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| WebView 页面加载慢 | 中 | 设置超时（10s），禁止加载图片/媒体资源 |
| 内存泄漏 | 中 | 使用 Application Context，用完及时 destroy |
| 后台 WebView 限制 | 低 | Android 对后台 WebView 无特殊限制，但需在主线程操作 |
| JS 注入安全 | 低 | 只注入预打包的可信 JS，不执行用户输入 |
| 部分站点仍拦截 | 低 | WebView 是真实浏览器，绝大多数站点不会拦截 |

---

## 6. 推荐方案

**推荐方案 A（WebView + Readability.js + Turndown），辅以方案 C 的降级策略：**

### 架构设计

```
WebContentExtractor (接口)
├── OkHttpExtractor (快速路径，现有实现)
│   └── Readability4J + 简单 text 输出
└── WebViewExtractor (WebView 路径，新增)
    └── Readability.js + Turndown.js → Markdown

WebContentFetcher (策略调度)
├── 先尝试 OkHttp（< 1s）
├── 检测反爬失败（403 / Cloudflare 特征 / 空内容）
└── 降级到 WebView（3-10s）
```

### 实现优先级

1. **P0**：封装 `WebViewExtractor` — 隐藏 WebView + JS 注入 + Markdown 输出
2. **P0**：将 readability.js + turndown.js 打包到 assets
3. **P1**：实现 `WebContentFetcher` 降级策略（OkHttp → WebView）
4. **P1**：WebView 实例池/复用机制（避免频繁创建销毁）
5. **P2**：WebView 搜索引擎支持（用 WebView 加载搜索页面，提升搜索成功率）
6. **P2**：页面加载优化（禁止图片/CSS/字体加载，减少等待时间）

### 预计工作量

- 核心 WebViewExtractor：~300-500 行 Kotlin
- JS 桥接层：~100 行 JS
- 降级策略 + 集成：~200 行 Kotlin
- 测试和调优：需真机验证

---

## 7. 结论

通过 WebView 实现端上爬虫是**完全可行且推荐的方案**。核心理由：

1. Android WebView 就是真实的 Chrome 浏览器内核，天然具备过反爬的能力
2. Readability.js + Turndown.js 是成熟的 JS 生态组合，体积小、MIT 许可
3. saga-reader 已在桌面端验证了 WebView 模拟器 + 内容提取的可行性
4. 与现有 OkHttp 方案形成互补，不需要完全替换
5. 无需引入额外的原生依赖，所有内容提取逻辑在 WebView JS 沙箱内完成
