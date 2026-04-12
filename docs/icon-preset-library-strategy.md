# Icon 生成策略：预置图标库 vs SVG 制作 Skill

> 2026-04-11
>
> 结论文档：回答"如何让模型稳定地为生成 App 设计出图形正常的 launcher icon"。

## 问题陈述

当前 `UpdateProjectIconTool` 让 LLM 直接手写 Android `<vector>` 的
`android:pathData`（背景 + 前景两份完整 XML）。实践中：

- 产出的 icon **基本没有可读性**，经常是歪斜线条、错位图形、或干脆退化成
  色块 + 文字。
- Agent system prompt 已经给出"108×108 viewport / 66×66 安全区 / 简单几何"
  等规范，但规范解决不了**坐标几何**问题。

**根因**：LLM 逐 token 生成 SVG path 的相对/绝对坐标、贝塞尔控制点时，需要
精确心算几何，这是当前模型（包括前沿模型）最不擅长的任务之一。学术界已有
明确证据：

- **SVGenius** / **SVGEditBench** / **VGBench** 三个 benchmark 都显示即便
  GPT-4 级模型在从零生成 SVG 路径时，正确率和视觉质量都显著低于"先检索再
  微调"的流水线。
- **Chat2SVG**（CVPR 2025）的核心结论就是：LLM 擅长"挑选 + 组合 + 着色"，
  不擅长"逐坐标绘制"。

所以问题不是提示词不够好，也不是模型不够聪明——是**任务形状本身就不适合
token 级生成**。

## 候选方案

### 方案 A：预置 SVG 图标库 + 参数化组装（推荐）

在 app 内 bundle 一个经过筛选的开源图标集子集，把 agent 的工作从"手绘
path"降级为"按语义搜索 → 选 id → 指定配色 → 工具自己转成 vector drawable"。

**数据源**：[iconify/icon-sets](https://github.com/iconify/icon-sets) —— 
150+ 个开源图标集的统一 JSON 格式，已清洗、网格对齐、viewBox 归一化、
single-path friendly。首批建议 bundle：

| 图标集 | 数量 | 用途 | 体积（gzip JSON） |
|---|---|---|---|
| Lucide | ~1500 | 线性图标兜底 | ~600 KB |
| Material Symbols Outlined 子集 | ~1000 | 功能类 App（计算器/日历/天气…） | ~500 KB |
| Phosphor Fill 子集 | ~500 | 需要实心前景的场景 | ~300 KB |

合计 ≈ 1.5 MB，放到 `app/src/main/assets/icons/` 下，对 APK 体积可接受
（`build-engine/assets/*.zip` 已经显著大于这个数量）。

**新的 Agent 工具形状**：

```
search_icon(keyword)                      → [{id, name, tags, preview}]
update_project_icon(
    iconId,
    foregroundColor,          # #RRGGBB
    backgroundStyle,          # "solid" | "linearGradient" | "radialGradient"
    backgroundColor1,
    backgroundColor2?         # gradient 专用
)
```

工具内部：
1. 从 JSON 里按 id 取出 SVG path data。
2. 在 Kotlin 里做一次 **SVG → Vector Drawable** 的结构化转换（iconify 的
   图标是 pre-cleaned 的 single path + viewBox，转换逻辑极简：`d="…"` → 
   `android:pathData="…"`，配色从参数注入，viewBox 映射到
   `android:viewportWidth/Height`，再按 108×108 + 66×66 safe zone
   rescale 到前景画布中央）。
3. 写入 `ic_launcher_background.xml` / `ic_launcher_foreground.xml`，
   复用现有 `ProjectIconRenderer` 继续生成 PNG fallback。

**保留后门**：为极少数真正需要自定义图形的场景保留
`update_project_icon_custom(backgroundXml, foregroundXml)`，但在 system
prompt 中明确标记为"仅在 `search_icon` 完全搜不到可用图形时使用"。

**参考开源实现**（都可以直接 port 或作为逻辑参考）：

- [Ashung/svg2vectordrawable](https://github.com/Ashung/svg2vectordrawable)
  —— 最成熟的 SVG→VectorDrawable 转换器，JavaScript，转换规则清晰。
- [SuLG-ik/svg2vector-mcp](https://github.com/SuLG-ik/svg2vector-mcp)
  —— 最近的 MCP server，做的事情和我们想做的几乎完全一致，证明"工具化
  转换"路线已经有人验证。
- [awssat/mcp-universal-icons](https://github.com/awssat/mcp-universal-icons)
  —— Lucide / Material / Heroicons / Tabler / Phosphor 的统一 MCP 检索
  服务，参考它的 id 命名与 fuzzy search 策略即可。
- Android Studio 内置的 `com.android.ide.common.vectordrawable.Svg2Vector`
  —— AOSP/AGP 里的官方实现（Apache 2.0），Kotlin 侧可以参考它的 path
  解析和 transform 展平。

### 方案 B：给模型一个"SVG 制作 Skill"

写一份 `skill` / prompt 文档，教模型 SVG path 语法、常见图形公式（圆 = 
4 段 cubic bezier，圆角矩形 = M + 4 × Q，等等），把常用形状预先写成
可复制的 path 片段。

**问题**：
1. **根因没解决**。模型的瓶颈不是"不会 SVG 语法"而是"算不准坐标"。教它
   语法等于教它写更长的错代码。
2. **文档长度不可控**。要做到"足够覆盖大部分 app 类别"，skill 文档会膨胀
   到几千 token，每轮 agent 都要载入，极度不划算。
3. **无质量下限**。哪怕给了片段，模型照样会自信地改坐标、错位组合，产出
   依然不稳定。
4. **违反 YAGNI**。如果图标库已经能覆盖 90%+ 场景，剩下 10% 用后门就够。

### 方案 C：继续优化 system prompt（现状）

已经试过，不 work。不讨论。

## 推荐：方案 A（预置库 + 参数化组装 + 保留后门）

核心理由一句话：**把"画图"外包给人类设计师，把"选图 + 配色"留给 LLM。**

- 图标库里的每个图形都是人类设计师画的，有网格对齐、笔画粗细统一、视觉
  重量平衡——这是 LLM 永远赢不了的地方。
- LLM 真正擅长的是语义匹配（"计算器 app" → `calculator` icon）和审美
  决策（"给记账 app 配什么背景色"），把它的工作限定在这里。
- 后门保留了创意空间，用户说"给我画一个喷火的龙"时还有路可走。

## 实施大致顺序（非本文档范围，后续做 spec）

1. 选型 + bundle 图标集（决定 Lucide 还是 Material Symbols 优先，确认
   体积 / license，各图标集都是 ISC / Apache 2.0 / OFL，均可）。
2. Kotlin 侧写最小化的 SVG-path → Android-pathData 转换器（iconify
   JSON 不含 `<g transform>` / filter / gradient，转换工作量很小）。
3. 改造 `UpdateProjectIconTool`：拆成 `search_icon` + 新
   `update_project_icon`（id + 配色参数）+ 后门
   `update_project_icon_custom`。
4. 改 system prompt 的 "App Icon Requests" 段，强力引导使用
   `search_icon` 流程。
5. 在 5–10 个典型 app 类别上做回归（计算器、天气、记事本、待办、天气、
   商店、音乐、健身、聊天、日历），每类看 3 次生成结果是否都"图形正常"。

## 不推荐的做法（避坑）

- **不要尝试在 device 上接入 iconify 的 HTTP API**——离线才是这个产品
  的正确形态，生成 APK 不应该依赖外网图标服务。
- **不要用 AI 生图模型**（Stable Diffusion / 云端生图）生成 launcher
  icon——体积、延迟、版权、合规全都是坑，而且 ic_launcher 是 vector
  drawable，位图路线还得再做一次矢量化。
- **不要一次 bundle 全部 iconify 图标集**——150+ 个图标集解压后会有
  几百 MB，一定要挑 2–3 个子集。

## Sources

- [iconify/icon-sets (GitHub)](https://github.com/iconify/icon-sets)
- [Ashung/svg2vectordrawable](https://github.com/Ashung/svg2vectordrawable)
- [SuLG-ik/svg2vector-mcp](https://github.com/SuLG-ik/svg2vector-mcp)
- [awssat/mcp-universal-icons](https://github.com/awssat/mcp-universal-icons)
- [Lucide Icons](https://lucide.dev/)
- [SVGenius benchmark](https://arxiv.org/html/2506.03139v1)
- [SVGEditBench](https://arxiv.org/html/2404.13710v1)
- [Chat2SVG](https://chat2svg.github.io/)
