# VibeApp UI 主题分析与改动评估

> 目标：评估修改页面背景色、主题色、按钮颜色的改动范围和难度。

## 结论

**改动非常小。** 项目的 Material 3 主题集成度很高，几乎所有 UI 组件都通过 `MaterialTheme.colorScheme` 引用颜色，只需修改 `Color.kt` 中的颜色常量即可全局生效。

---

## 当前主题架构

```
DataStore (持久化)
  └─ SettingDataSource: dynamic_mode / theme_mode
        ↓
ThemeViewModel → CompositionLocals
  ├─ LocalDynamicTheme (ON / OFF)
  └─ LocalThemeMode (SYSTEM / DARK / LIGHT)
        ↓
GPTMobileTheme() ← 入口在 MainActivity.setContent {}
  └─ MaterialTheme(colorScheme, typography)
        ↓
全部 UI 组件通过 MaterialTheme.colorScheme.* 取色
```

### 核心文件（共 3 个）

| 文件 | 职责 |
|------|------|
| `presentation/theme/Color.kt` | 所有颜色常量定义（light / dark / 中对比度 / 高对比度） |
| `presentation/theme/Theme.kt` | ColorScheme 组装、`GPTMobileTheme()` 入口、动态主题 & 暗色模式切换 |
| `presentation/theme/Type.kt` | 排版定义（目前使用 Material 3 默认值） |

---

## 当前配色方案

### Light 主色调

| Token | 色值 | 用途 |
|-------|------|------|
| `primary` | `#1A6B51` 青绿 | 按钮、图标高亮、主操作 |
| `onPrimary` | `#FFFFFF` | primary 上的文字/图标 |
| `primaryContainer` | `#A4F2D1` | 用户聊天气泡背景 |
| `background` | `#F5FBF5` | 页面背景 |
| `surface` | `#F5FBF5` | 卡片/面板表面 |
| `surfaceVariant` | `#DAE5DC` | 项目图标背景、思考区块 |
| `error` | `#BA1A1A` | 错误提示、删除按钮 |
| `tertiary` | `#3F6375` | 成功状态 badge |

### Dark 主色调

| Token | 色值 | 用途 |
|-------|------|------|
| `primary` | `#8AD6B6` 浅青 | 同上 |
| `primaryContainer` | `#005138` | 用户聊天气泡背景 |
| `background` | `#0F1512` | 页面背景 |
| `surface` | `#0F1512` | 卡片/面板表面 |

---

## 三项改动的评估

### 1. 修改页面背景色

**改动量：极小（改 1 个文件的 2-4 个值）**

| 需要改的 | 位置 |
|----------|------|
| `backgroundLight` / `backgroundDark` | `Color.kt` |
| `surfaceLight` / `surfaceDark`（当前与 background 同色） | `Color.kt` |

**原因：** 全部页面通过 Scaffold 或直接引用 `MaterialTheme.colorScheme.background` 取背景色，没有硬编码。改一处即全局生效。

**需要注意：** 如果 background 和 surface 当前保持同色（现在就是），改 background 时建议同步调整 surface 系列色值，否则某些卡片/面板会与背景产生不协调。

### 2. 修改主题色（primary）

**改动量：极小（改 1 个文件的 4-8 个值）**

| 需要改的 | 位置 |
|----------|------|
| `primaryLight` / `primaryDark` | `Color.kt` |
| `onPrimaryLight` / `onPrimaryDark` | `Color.kt` |
| `primaryContainerLight` / `primaryContainerDark` | `Color.kt` |
| `onPrimaryContainerLight` / `onPrimaryContainerDark` | `Color.kt` |

**原因：** 按钮、图标高亮、聊天气泡、选中态等都引用 `primary` 系列 token。

**推荐做法：** 使用 [Material Theme Builder](https://m3.material.io/theme-builder) 输入新的 primary 种子色，一键导出完整的 light/dark 色板，替换 `Color.kt` 中对应的值。这样能保证对比度和可访问性合规。

### 3. 修改按钮颜色

**改动量：极小（通常无需额外改动）**

按钮样式全部使用 Material 3 默认值：

| 按钮类型 | 当前取色 | 出现位置 |
|----------|----------|----------|
| `Button`（填充按钮） | `primary` / `onPrimary` | PrimaryLongButton、各主操作 |
| `TextButton` | `primary`（文字色） | 对话框确认、平台选择 |
| `IconButton` | 继承父级内容色 | 导航栏、工具栏 |
| `OutlinedButton` | `primary`（边框+文字） | Setup 向导 |
| `FloatingActionButton` | `primaryContainer` | 新建项目 |
| `ExtendedFAB`（删除） | `errorContainer` | 批量删除 |

**结论：** 如果修改了 primary 色，按钮颜色会自动跟随变化，不需要单独改按钮代码。如果希望按钮使用独立于 primary 的颜色，则需要在各按钮处添加自定义 `ButtonDefaults.buttonColors()`，但一般不建议这样做。

---

## 硬编码颜色排查

整个 UI 层仅发现 **1 处**硬编码颜色：

| 位置 | 色值 | 用途 | 是否需要改 |
|------|------|------|-----------|
| `ChatBubble.kt` — `GPTMobileIcon` | `Color(0xFF00A67D)` | AI 助手头像背景 | 可选（品牌色，可保留） |

其余所有颜色均通过 `MaterialTheme.colorScheme.*` 引用，主题一致性非常好。

---

## 推荐改动流程

1. 在 [Material Theme Builder](https://m3.material.io/theme-builder) 中选择新的种子色
2. 导出 Compose 主题代码，获取完整的 light / dark 色板
3. 替换 `Color.kt` 中的颜色常量
4. `Theme.kt` 和 UI 代码**无需任何修改**
5. 如需调整中对比度 / 高对比度方案，同步替换对应变量
6. 验证 light / dark 两种模式下的视觉效果

**预计改动：仅 `Color.kt` 一个文件，替换颜色值即可。**
