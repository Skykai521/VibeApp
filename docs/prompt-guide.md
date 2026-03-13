# Prompt 工程指南 | Prompt Guide

本文档说明 VibeApp 中所有 Prompt 模板的设计原则和修改方法。

## 文件位置

```
app/src/main/assets/prompts/
├── codegen.md          # 代码生成 System Prompt
├── fixloop.md          # 错误修复 System Prompt
├── whitelist.json      # 允许的 SDK API 列表
└── blacklist.json      # 禁止的 API 列表
```

## Prompt 设计原则

### 1. 明确输出格式

AI 的输出必须是机器可解析的。不要让 AI 自由发挥输出格式。

```
❌ 不好的：请生成代码
✅ 好的：严格按照以下格式输出，每个文件以 ---FILE: filename--- 开头
```

### 2. 提供完整示例

Few-shot 比 zero-shot 稳定得多。始终在 Prompt 中包含 2-3 个完整的输入输出示例。

### 3. 负面约束优先

明确告诉 AI **不能**做什么，比告诉它能做什么更重要。

```
❌ 模糊的：使用标准 Android API
✅ 明确的：不要使用以下内容：
   - 任何第三方库（如 Retrofit, Glide, OkHttp）
   - 反射 API（java.lang.reflect.*）
   - native 方法或 JNI
   - Kotlin 语法
```

### 4. 上下文最小化

只提供必要的上下文。过多上下文会稀释关键约束。

## 修改 Prompt 的流程

1. 在 `assets/prompts/` 中修改对应文件
2. 使用至少 10 个不同的用户输入测试
3. 记录编译成功率变化
4. PR 中附上测试结果对比

## 白名单维护

`whitelist.json` 格式：

```json
{
  "allowed_imports": [
    "android.app.Activity",
    "android.os.Bundle",
    "android.widget.TextView",
    "android.widget.Button",
    "android.widget.EditText",
    "android.widget.LinearLayout",
    "android.widget.RelativeLayout",
    "android.widget.Toast",
    "android.view.View",
    "android.view.ViewGroup",
    "android.graphics.Color",
    "android.graphics.Canvas",
    "android.graphics.Paint",
    "android.content.SharedPreferences",
    "android.util.Log"
  ],
  "allowed_packages": [
    "java.lang",
    "java.util",
    "java.io",
    "java.text",
    "java.math"
  ]
}
```

添加新的白名单项时需要：
- 确认该 API 在 android.jar (API 26) 中存在
- 编写一个使用该 API 的测试用例并确认编译成功
- 更新 System Prompt 中的白名单说明
