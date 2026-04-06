# Jsoup 网络能力集成方案

> 为生成的 App 提供通用的网络请求 + HTML 解析能力，让 AI 可以生成天气、新闻、数据抓取等需要网络访问的应用。

## 背景与动机

用户通过 VibeApp 生成的 App 目前**没有任何网络能力**——没有 HTTP 库、没有 INTERNET 权限。这导致天气、新闻、汇率等需要实时数据的应用无法实现。

**需求：**
- 终端用户装上生成的 App 就能直接使用，零配置
- 不需要用户注册或配置 API key
- 通用的网络请求 + HTML 解析能力，AI 自己编写具体的爬虫/解析逻辑

## 技术选型

### 候选方案对比

| 方案 | Android 兼容 | JAR 体积 | 依赖数 | HTTP + HTML 解析 | AI 生成难度 |
|------|:---:|:---:|:---:|:---:|:---:|
| **Jsoup（推荐）** | 完全兼容 | ~440 KB | 0 | 都支持 | 极低 |
| OkHttp + Jsoup | 兼容 | ~3.2 MB | 3（okio, kotlin-stdlib） | 都支持 | 中等 |
| HttpURLConnection + Jsoup | 兼容 | ~440 KB | 0 | 都支持 | 中高（HTTP 部分样板多） |
| HtmlUnit | 不兼容 | ~10 MB+ | 15+ | 都支持 | — |
| Volley | 部分（AAR） | — | — | 仅 HTTP | — |

### 选择 Jsoup 的理由

1. **单 JAR 零依赖** — 完美适配 javac + D8 编译链，无需处理依赖传递
2. **同时覆盖 HTTP 和 HTML 解析** — `Jsoup.connect(url).get()` 一行完成请求+解析
3. **API 极简** — AI 生成代码的成功率高，减少编译错误
4. **~440 KB** — 对 APK 体积影响极小
5. **Android 久经验证** — 内部基于 `HttpURLConnection`，Android 原生支持
6. **纯 Java** — 与生成 App 的 Java 8 源码级别完全匹配

### 排除的方案

- **HtmlUnit**：依赖 `java.awt`、Xerces 等 Android 不存在的类，不可行
- **OkHttp**：需要 kotlin-stdlib（~1.7 MB），对于爬虫场景 overkill
- **Volley**：AAR 格式不适合 javac + D8 编译链，且不支持 HTML 解析
- **Retrofit**：依赖链过长（OkHttp + Gson/Moshi + annotation processing）

## 集成设计

### 架构概览

Jsoup 作为**内置库**提供给所有生成的 App，与 AndroidX、shadow-runtime 同级。AI 生成代码时可直接 `import org.jsoup.Jsoup` 使用。

```
build-engine/src/main/assets/
├── rt.zip                      # Android API stubs
├── lambda-stubs.zip            # Java 8 lambda 支持
├── androidx-classes.jar.zip    # AndroidX 核心库
├── shadow-runtime.jar          # ShadowActivity 运行时
└── jsoup.jar.zip               # [新增] Jsoup 网络+解析库
```

### 需要变更的文件

#### 1. 新增文件

| 文件 | 说明 |
|------|------|
| `build-engine/src/main/assets/jsoup.jar.zip` | Jsoup JAR 的 zip 压缩包 |

#### 2. 修改文件

| 文件 | 变更内容 |
|------|----------|
| `BuildWorkspace.kt` | 新增 `jsoupJar` 属性，`prepare()` 时解压 |
| JavacCompiler classpath 构建处 | 将 `jsoupJar` 加入编译类路径 |
| `templates/.../AndroidManifest.xml` | 添加 `<uses-permission android:name="android.permission.INTERNET"/>` |
| `agent-system-prompt.md` | 添加 Jsoup 可用性说明和使用示例 |

### BuildWorkspace 变更

```kotlin
// 新增属性
val jsoupJar: File get() = File(libsDir, "jsoup.jar")

// prepare() 中新增解压
extractAsset("jsoup.jar.zip", jsoupJar)
```

### Classpath 变更

```kotlin
// 现有 classpath 构建逻辑中添加 jsoupJar
val classpath = input.classpathEntries.map(::File).filter { it.exists() } +
    listOfNotNull(workspace.androidxClassesJar) +
    listOfNotNull(workspace.shadowRuntimeJar) +
    listOfNotNull(workspace.jsoupJar) +   // [新增]
    workspace.classesDir
```

### AndroidManifest.xml 模板变更

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$packagename"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk android:minSdkVersion="${minSdkVersion}" android:targetSdkVersion="${targetSdkVersion}"/>

    <!-- 新增：网络权限 -->
    <uses-permission android:name="android.permission.INTERNET"/>

    <application ...>
        ...
    </application>
</manifest>
```

### Agent System Prompt 补充内容

需要在 `agent-system-prompt.md` 中添加以下信息，让 AI 知道 Jsoup 可用：

```markdown
## 网络访问能力

项目已内置 Jsoup 库，可直接使用：

- `import org.jsoup.Jsoup` 即可使用，无需额外依赖
- INTERNET 权限已默认声明

### 关键规则

- **网络请求必须在子线程执行**（Android 禁止主线程网络访问）
- 使用 `new Thread()` 或 `ExecutorService` 包裹网络代码
- 通过 `runOnUiThread()` 回到主线程更新 UI

### 常见用法

抓取网页内容：
  Document doc = Jsoup.connect("https://example.com").get();
  Elements items = doc.select("css selector");

获取 JSON/API 响应：
  String json = Jsoup.connect("https://api.example.com/data")
      .ignoreContentType(true)
      .execute()
      .body();

POST 请求：
  Document doc = Jsoup.connect("https://example.com/login")
      .data("username", "user")
      .data("password", "pass")
      .post();
```

## 注意事项

### 线程模型

Android 强制要求网络操作不能在主线程（否则抛出 `NetworkOnMainThreadException`）。生成的 App 是 Java 8，没有 Kotlin 协程，因此 AI 必须使用传统线程方案：

```java
new Thread(() -> {
    try {
        Document doc = Jsoup.connect(url).get();
        String result = doc.select(".weather").text();
        runOnUiThread(() -> textView.setText(result));
    } catch (IOException e) {
        e.printStackTrace();
    }
}).start();
```

### Jsoup 的 HTTP 能力边界

Jsoup 的 HTTP 功能足以覆盖大部分爬虫场景，但有局限：
- 不支持 WebSocket
- 不支持 HTTP/2
- 无连接池（每次请求新建连接）
- 不适合高频 API 调用场景

对于生成 App 的典型用例（天气查询、新闻抓取、简单 API 调用），这些限制不构成问题。

### JSON 解析

Jsoup 可以获取 JSON 响应体（`execute().body()` 返回原始字符串），但不提供 JSON 解析。AI 生成代码时可以使用：
- Android 内置的 `org.json.JSONObject` / `org.json.JSONArray`（无需额外依赖）
- 手动字符串解析（简单场景）
