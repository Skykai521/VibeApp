# AI 代码生成策略 | AI Strategy

## 核心原则

VibeApp 的 AI 代码生成遵循一个核心原则：**约束优于自由**。

给 AI 越多的自由度，生成的代码就越不可控。通过严格的模板约束和 Prompt 工程，将 AI 的创造力限制在可编译、可运行的范围内。

## 模板系统

### 设计理念

AI 不从零开始生成整个项目，而是在预定义的项目骨架内"填空"。

```
模板提供：             AI 负责：
├── AndroidManifest.xml  (固定结构)
├── MainActivity.java    → AI 填充 onCreate() 内的业务逻辑
├── activity_main.xml    → AI 生成布局内容
└── strings.xml          → AI 填充字符串资源
```

### 单 Activity 模板（Phase 1）

```java
// template: single_activity/MainActivity.java
package {{PACKAGE_NAME}};

import android.app.Activity;
import android.os.Bundle;
// {{IMPORTS}} — AI 填充所需 import

public class MainActivity extends Activity {

    // {{FIELDS}} — AI 填充成员变量

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // {{INIT_CODE}} — AI 填充初始化逻辑
    }

    // {{METHODS}} — AI 填充自定义方法
}
```

### 模板变量

| 变量 | 说明 |
|------|------|
| `{{PACKAGE_NAME}}` | 包名，系统生成 |
| `{{IMPORTS}}` | AI 填充的 import 语句 |
| `{{FIELDS}}` | AI 填充的成员变量 |
| `{{INIT_CODE}}` | AI 填充的 onCreate 初始化逻辑 |
| `{{METHODS}}` | AI 填充的自定义方法 |
| `{{LAYOUT_CONTENT}}` | AI 填充的 XML 布局内容 |
| `{{STRING_RESOURCES}}` | AI 填充的字符串资源 |

## Prompt 工程

### System Prompt 结构

```
[角色定义]
你是一个 Android 代码生成器，专门生成可以在设备端编译的 Java + XML 代码。

[严格约束]
1. 只使用白名单中的 Android SDK API
2. 不使用任何第三方库
3. 不使用反射、JNI、或 native 代码
4. Java 8 语法，不使用 lambda（ECJ 兼容性）
5. XML 布局使用基础 View 组件

[白名单] (附 whitelist.json 内容)

[输出格式]
严格按照以下格式输出，不要输出其他内容：
---FILE: MainActivity.java---
(代码内容)
---FILE: activity_main.xml---
(布局内容)
---FILE: strings.xml---
(字符串资源)

[示例]
(提供 2-3 个完整的输入输出示例)
```

### 关键约束细节

**为什么不用 lambda？**
ECJ 支持 Java 8 lambda，但 D8 的 desugaring 在设备端可能有边界 case。Phase 1 保守策略，优先保证编译成功率。

**为什么不用 AppCompat？**
AppCompatActivity 等 Jetpack 库需要额外的依赖解析和预编译 DEX，Phase 1 直接使用 `android.app.Activity`。

**白名单范围（Phase 1）**：
- `android.app.Activity`
- `android.os.Bundle`, `android.os.Handler`
- `android.widget.*`（TextView, Button, EditText, ListView, ImageView 等）
- `android.view.*`（View, ViewGroup, LayoutInflater 等）
- `android.graphics.*`（Canvas, Paint, Color, Bitmap 等）
- `android.content.SharedPreferences`
- `android.widget.Toast`
- `android.util.Log`
- `java.util.*`, `java.lang.*`, `java.io.*`

### 错误修复 Prompt

```
[角色] 你是 Android 代码修复专家。

[上下文]
以下代码编译失败，错误信息如下：
{{ERROR_LOG}}

原始代码：
{{SOURCE_CODE}}

[要求]
1. 分析错误原因
2. 输出修复后的完整代码（不要只输出 diff）
3. 确保只使用白名单 API
4. 修复后的代码必须保持原有功能意图

[输出格式] (同上)
```

## 响应解析

AI 响应需要可靠地解析出各文件内容：

```kotlin
class AiResponseParser {
    // 解析 ---FILE: xxx--- 格式
    fun parse(response: String): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val pattern = Regex("""---FILE:\s*(.+?)---\n([\s\S]*?)(?=---FILE:|$)""")
        pattern.findAll(response).forEach { match ->
            val filename = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()
            files[filename] = content
        }
        return files
    }
}
```

**健壮性保障**：
- 支持多种分隔符格式（`---FILE:`, `// FILE:`, ````java` 等）
- AI 有时会在代码前后添加解释文本，需要过滤
- 使用正则 + 启发式规则提取代码块

## 多模型适配

不同模型的 Prompt 可能需要微调：

| 模型 | 特点 | 适配策略 |
|------|------|---------|
| Claude | 遵循指令能力强 | 标准 Prompt 即可 |
| GPT-4o | 倾向添加额外解释 | 强调"只输出代码" |
| DeepSeek | 中文理解好，但可能混入第三方库 | 加强白名单约束 |
| Ollama (本地) | 能力受模型大小限制 | 简化模板，减少约束复杂度 |

## 评估指标

| 指标 | 目标 | 说明 |
|------|------|------|
| 首次编译成功率 | > 60% | 预检通过后直接编译成功 |
| 修复后编译成功率 | > 90% | 经过自动修复循环 |
| 预检拦截率 | > 80% | 明显错误在预检阶段拦截 |
| 平均修复次数 | < 2 | 大多数问题 1-2 次修复搞定 |
| 功能符合率 | > 70% | 生成的 App 功能符合用户描述 |
