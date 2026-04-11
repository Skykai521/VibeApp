# ChatScreen 语音输入功能调研

> 调研日期: 2026-04-10

## 目录

1. [当前 ChatScreen 输入架构](#1-当前-chatscreen-输入架构)
2. [Android 语音识别方案对比](#2-android-语音识别方案对比)
3. [开源项目调研](#3-开源项目调研)
4. [已接入 API 的语音能力分析](#4-已接入-api-的语音能力分析)
5. [推荐方案](#5-推荐方案)
6. [UX 设计建议](#6-ux-设计建议)

---

## 1. 当前 ChatScreen 输入架构

### 输入框结构 (`ChatInputBox`)

位置: `presentation/ui/chat/ChatScreen.kt` (约 L1094-1234)

当前输入框由 `BasicTextField` 构成，布局如下:

```
┌──────────────────────────────────────┐
│  [图片缩略图行 - 可横向滚动]          │
│  ┌────┐ ┌──────────────┐ ┌────────┐  │
│  │ 📎 │ │  文本输入区域  │ │ 发送/停 │  │
│  └────┘ └──────────────┘ └────────┘  │
└──────────────────────────────────────┘
  左侧: 文件选择     中间: 文本    右侧: 发送/停止
```

### 输入流程

1. 用户输入文本 → `ChatViewModel.updateQuestion(s)` → 更新 `_question` StateFlow
2. 点击发送 → `ChatViewModel.askQuestion()` → 创建 `MessageV2` → `completeChat()` → `AgentSessionManager.startSession()` → `AgentLoopCoordinator.run()`
3. 语音输入只需将识别结果填入 `_question` 即可，不影响下游 Agent 流程

**关键结论**: 语音输入本质上只是文本输入的另一种入口。识别后的文字填入输入框，用户确认后发送，无需修改 Agent 侧逻辑。

---

## 2. Android 语音识别方案对比

### 方案 A: Android SpeechRecognizer API (系统内置)

| 项目 | 说明 |
|------|------|
| **成本** | 免费 |
| **依赖** | 无额外依赖，系统 API |
| **离线支持** | API 31+ (Android 12+) 支持下载语言包后离线识别 |
| **在线模式** | 默认发送到 Google 服务器，准确率更高 |
| **语言** | 取决于用户设备已安装的语言包 |
| **集成难度** | 低 — 使用 `RecognizerIntent` 或 `SpeechRecognizer` 即可 |
| **Compose 集成** | 通过 `rememberLauncherForActivityResult` 调用 |

**ML Kit GenAI Speech Recognition (2025-2026 新增)**:
- "Basic mode" 使用传统模型 (API 31+)
- "Advanced mode" 使用 GenAI 模型，准确率更高 (目前限 Pixel 10+)

**优点**: 零成本、零依赖、集成简单、覆盖全部 Android 设备
**缺点**: 离线准确率有限、OEM 行为不一致、流式/部分结果控制有限

### 方案 B: OpenAI Whisper API (云端)

| 项目 | 说明 |
|------|------|
| **成本** | `whisper-1`: $0.006/min; `gpt-4o-transcribe`: $0.006/min; `gpt-4o-mini-transcribe`: $0.003/min |
| **依赖** | 复用现有 OpenAI 网络层 |
| **离线支持** | 无 |
| **语言** | 99 种语言 |
| **集成难度** | 中 — 需录音 + 文件上传 |
| **准确率** | 非常高 |

**优点**: 准确率最高、语言最全、可复用已有 OpenAI API Key
**缺点**: 需要网络、有费用、需自行处理录音和上传

### 方案 C: 本地开源模型 (Vosk / Whisper.cpp / Sherpa-ONNX)

详见下方 [开源项目调研](#3-开源项目调研)。

---

## 3. 开源项目调研

### 3.1 Vosk

| 项目 | 说明 |
|------|------|
| **License** | **Apache 2.0** ✅ 商业友好 |
| **GitHub** | [alphacep/vosk-api](https://github.com/alphacep/vosk-api) |
| **离线** | 完全离线 |
| **语言** | 20+ 种 (含中文、英文、日文等) |
| **Android 集成** | Maven: `org.vosk:vosk-android:0.3.40+` |
| **模型大小** | ~50 MB (small 模型) |
| **状态** | 活跃维护 (2026-01 更新) |

优点: 轻量模型、Maven 直接引入、Apache 许可、支持流式实时识别、部分结果回调
缺点: 准确率不如 Whisper 系列

### 3.2 Whisper.cpp

| 项目 | 说明 |
|------|------|
| **License** | **MIT** ✅ 商业友好 |
| **GitHub** | [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp) |
| **离线** | 完全离线 |
| **语言** | 99 种 |
| **Android 集成** | 通过 JNI/NDK，仓库内有 Kotlin 示例 |
| **模型大小** | tiny ~75 MB, base ~150 MB, small ~500 MB |
| **状态** | 非常活跃 |

优点: 准确率最佳、语言覆盖最广、MIT 许可
缺点: 模型较大、内存要求高 (2-4 GB)、JNI 集成复杂度较高、低端设备可能较慢

### 3.3 WhisperKit Android (Argmax)

| 项目 | 说明 |
|------|------|
| **License** | **MIT** ✅ |
| **GitHub** | [argmaxinc/WhisperKitAndroid](https://github.com/argmaxinc/WhisperKitAndroid) |
| **说明** | 基于 Google LiteRT 的 Whisper 端侧推理，Kotlin-first SDK |
| **状态** | 原仓库迁移中 → `argmax-sdk-kotlin` |

### 3.4 Sherpa-ONNX (Next-gen Kaldi)

| 项目 | 说明 |
|------|------|
| **License** | **Apache 2.0** ✅ |
| **GitHub** | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| **离线** | 完全离线 |
| **语言** | 多种 (中英日韩粤等) |
| **Android 集成** | Java 绑定，有 Android 示例 |
| **功能** | STT + TTS + 说话人识别 + VAD + 语音增强 |
| **状态** | 非常活跃 (2026 年频繁更新) |

优点: 功能全面、模型可选范围广、Apache 许可
缺点: 仅 Java 绑定 (非 Kotlin-first)、文档导航较复杂

### 3.5 Mozilla DeepSpeech — ❌ 已停止维护

2025 年 6 月正式归档，不建议在新项目中使用。

### 开源方案总结对比

| 库 | License | 离线 | 语言 | 模型大小 | 准确率 | 集成难度 | 状态 |
|---|---|---|---|---|---|---|---|
| **Vosk** | Apache 2.0 | ✅ | 20+ | ~50 MB | 良好 | 低 (Maven) | 活跃 |
| **Whisper.cpp** | MIT | ✅ | 99 | 75MB-3GB | 最佳 | 中 (JNI) | 活跃 |
| **WhisperKit** | MIT | ✅ | 99 | 可变 | 最佳 | 中 (Kotlin SDK) | 过渡中 |
| **Sherpa-ONNX** | Apache 2.0 | ✅ | 多种 | 可变 | 优秀 | 中 (Java) | 非常活跃 |
| ~~DeepSpeech~~ | ~~MPL 2.0~~ | ~~✅~~ | — | — | — | — | **已停止** |

---

## 4. 已接入 API 的语音能力分析

VibeApp 当前接入 6 个 AI 提供商，其语音/音频能力如下:

| 提供商 | 当前接入 | 有语音 API | 说明 |
|--------|---------|-----------|------|
| **OpenAI** | ✅ | ✅ **Whisper API** | `whisper-1`, `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`; $0.003-0.006/min; 99 种语言; 可复用现有 API Key 和网络层 |
| **Anthropic** | ✅ | ❌ | Claude API 无音频转录端点。客户端 App 有语音功能但那是前端实现，非 API 能力。需先转文字再发送。 |
| **Qwen** | ✅ | ✅ **Qwen3-ASR** | DashScope 提供 ASR 模型 (`qwen3-asr-flash-realtime` 等); 52+ 种语言; 但使用 WebSocket 协议，与现有 REST/SSE 接入方式不同 |
| **Kimi** | ✅ | ❌ | 无公开语音 API |
| **MiniMax** | ✅ | ❌ | 无公开语音转录 API |
| **DeepSeek** | ✅ | ❌ | 无公开语音 API |

**结论**: 只有 OpenAI 和 Qwen 有现成的语音转录 API。其中 OpenAI Whisper 最成熟且可直接复用现有网络基础设施。

---

## 5. 推荐方案

### 推荐: 分层实现策略

#### 第一阶段 (MVP): Android SpeechRecognizer

**理由**: 零成本、零依赖、集成最快、覆盖所有设备

- 使用系统 `SpeechRecognizer` API
- 需要 `RECORD_AUDIO` 权限
- 识别结果填入 `ChatInputBox` 的文本框
- 在输入框右侧添加麦克风按钮 (发送按钮左侧)
- 中国用户在无 Google 服务设备上可能需要回退处理

```
┌────┐ ┌──────────────┐ ┌──┐ ┌────┐
│ 📎 │ │  文本输入区域  │ │🎤│ │ ➤  │
└────┘ └──────────────┘ └──┘ └────┘
```

#### 第二阶段 (增强): 可选的云端转录

- 在设置中增加"语音识别引擎"选项:
  - 系统默认 (SpeechRecognizer)
  - OpenAI Whisper (需已配置 OpenAI API Key)
- 当用户选择 Whisper 时，本地录音 → 上传 → 转录 → 填入输入框
- 复用 `OpenAIAPIImpl` 的 Ktor HttpClient，新增 `/v1/audio/transcriptions` 端点调用

#### 第三阶段 (可选): 离线高精度模型

- 集成 Vosk (Apache 2.0, Maven 直接引入, ~50MB 模型)
- 为无网络/无 Google 服务的场景提供兜底
- 模型可按需下载，不增加 APK 体积

### 不推荐的方案

| 方案 | 原因 |
|------|------|
| Whisper.cpp 端侧推理 | JNI 复杂度高、模型大、内存要求高，对移动端不够友好 |
| Google Cloud STT | 需额外 Google Cloud 账号和计费，用户门槛高 |
| Qwen ASR | WebSocket 协议与现有架构差异大，集成成本高 |
| DeepSpeech | 已停止维护 |

---

## 6. UX 设计建议

### 麦克风按钮位置与交互

- **位置**: 输入框内右侧，发送按钮左边
- 当文本框为空且未在响应中时显示麦克风图标
- 当文本框有内容时，麦克风按钮可收缩或保持不变 (允许追加语音输入)

### 录音交互模式

- **推荐: 点击切换模式** — 点击开始录音，再次点击结束
  - 适合较长的描述性输入 (符合 App 自然语言描述需求的场景)
  - 状态更清晰
- 可考虑后续支持长按说话模式

### 录音状态反馈

- 录音中显示脉冲动画 (麦克风图标 + 音量波形)
- 实时显示部分识别结果 (streaming partial results)
- 录音时间显示

### 识别结果处理

- **识别文字填入输入框，不自动发送** — 用户可编辑确认后再发送
- 识别失败时显示提示，允许重试
- 支持追加模式: 已有文本时，语音追加到光标位置

### 权限处理

- 首次点击麦克风时请求 `RECORD_AUDIO` 权限
- 提供清晰的权限说明 rationale
- 权限被永久拒绝时，引导用户到系统设置

### 无障碍

- 麦克风按钮需有 `contentDescription` (如 "语音输入")
- 键盘输入始终可用，语音只是辅助手段
