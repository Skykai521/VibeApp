# GrepProjectFilesTool 设计文档

> 状态：已 review，待开发
> 作者：AI assistant
> 关联模块：`app/src/main/kotlin/com/vibe/app/feature/agent/tool/`
> 关联已有工具：`ReadProjectFileTool`、`ListProjectFilesTool`、`EditProjectFileTool`

## 1. 背景与动机

当前 agent 工具集里，定位代码只能走两步：

1. `list_project_files` 拿到全部路径
2. 对候选文件逐个 `read_project_file` 把整文件塞进上下文

对于 Java + XML 生成项目，这条路径有两个痛点：

- **Token 浪费**：一个几百行的 `MainActivity.java` 或 `strings.xml` 经常几 KB，模型只是想确认某个符号是否存在，却被迫读完。
- **决策效率低**：模型在"我该读哪些文件"这一步得盲猜，往往要读 2–3 个文件才能定位到真正要改的那一个。

**结论**：引入 `grep_project_files` 工具，用 Kotlin 原生实现在项目工作区内做字面量/正则搜索，让模型先定位、再精读。同时升级 `list_project_files` 输出符号 outline，解决"模型该 grep 什么关键词"这个前置问题。

## 2. 开源方案调研

| 方案 | 是否可直接用 | 原因 |
|---|---|---|
| `BurntSushi/ripgrep` | ❌ | Rust 二进制，没有 C API / JNI 绑定；需要打多架构 NDK 包，显著增大 APK |
| `microsoft/vscode-ripgrep` | ❌ | 依赖 Node，Android 端不适用 |
| `cy6erGn0m/kgrep` | ❌ | Kotlin 的教学级实现，没有文件树遍历、glob、输出格式等工程特性 |
| `seqis/AI-grep` / `grepai` | ❌ | 桌面端/服务端索引工具，不适合单工程在端侧运行 |
| `grep.app` MCP | ❌ | 面向 GitHub 公共仓库，和本地工作区无关 |

**决策**：自己用 Kotlin 实现。VibeApp 的搜索范围是单个 `files/projects/{id}/app` 目录、文件总数有限（一般 < 200），用 `File.walkTopDown()` + `java.util.regex.Pattern` 足以满足性能需求，不需要引入任何第三方依赖。

## 3. 关键词来源问题与 outline 前置方案

### 3.1 问题

`grep_project_files` 要工作，模型必须先想出"要搜什么"。但模型进入新 turn 时手里只有用户的自然语言（如"改一下提交按钮颜色"），它不知道那个按钮在代码里叫 `btn_submit` 还是 `submitButton`。没有关键词，grep 就无从谈起，模型只能退化到 `read_project_file` 把整个 `MainActivity.java` 拉进来猜——和现状一样。

这个问题在 VibeApp 尤其突出：

- 工程是 AI 生成的，开发者不知道符号名
- 跨 turn 的代码可能是别的模型写的，当前模型没参与过生成
- Context compaction 之后，即使自己写的代码也记不住

所以工具层必须主动回答"这个工程里有哪些可 grep 的符号"，否则 grep 工具会空转。

### 3.2 方案：升级 `list_project_files` 返回符号 outline

把 `list_project_files` 从"路径列表"升级为"符号地图"。典型输出：

```text
AndroidManifest.xml: com.example.demo [MainActivity, SettingsActivity]
src/main/java/com/example/demo/MainActivity.java
  class MainActivity extends AppCompatActivity
  methods: onCreate, onSubmitClick, loadData
src/main/java/com/example/demo/SettingsActivity.java
  class SettingsActivity extends AppCompatActivity
  methods: onCreate
res/layout/activity_main.xml
  ids: btn_submit, tv_result, et_input
res/layout/activity_settings.xml
  ids: sw_notify, sw_dark_mode
res/values/strings.xml
  keys: app_name, btn_submit_label, hint_input
res/values/colors.xml
  keys: primary, accent, background
```

### 3.3 实现算法（纯正则，无需语法解析）

| 文件类型 | 抽取规则 |
|---|---|
| `*.java` | `class <Name>`、public/protected 方法头 `\b(public\|protected)\s+[\w<>\[\],\s]+?\s+(\w+)\s*\(` |
| layout `*.xml`（`res/layout*/`）| 根标签 + `android:id="@\+id/(\w+)"` |
| values `*.xml`（`res/values*/`）| 所有 `name="([^"]+)"` |
| `AndroidManifest.xml` | `package="..."` + `<activity android:name="([^"]+)"` |
| 其它 | 只列路径 |

单文件抽取上限：方法 20 个、id 30 个，超过就截断加 `…`。总 outline 大小上限 8 KB，防炸。

典型 VibeApp 工程 outline 大约 1–3 KB，比纯路径列表贵约 2–4×，但模型看过一次就知道可以 grep 什么，避免后续几次 `read_project_file`——净 token 开销大幅下降。

### 3.4 `list_project_files` schema 变更

新增可选参数：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `include_outline` | boolean | `true` | 设为 `false` 时退化为旧行为，只返回路径列表 |

默认 `true` 是故意的——让模型不做任何额外动作就能享受新行为；需要纯路径的极少数场景显式关掉即可。

输出格式（`include_outline = true`）：

```json
{
  "files": ["..."],
  "outline": "AndroidManifest.xml: com.example.demo [MainActivity]\n..."
}
```

兼容模式（`include_outline = false`）保持现状，只返回 `files`。

### 3.5 强约束写进 system prompt

只升级工具还不够，必须在 `agent-system-prompt.md` 里锁死工作流：

> **Locating code in existing projects**: On any turn that modifies existing code, your FIRST tool call MUST be `list_project_files`. Use the returned outline to identify target symbols (class names, method names, resource IDs, string keys), then `grep_project_files` for their exact usages, then `read_project_file` with `start_line`/`end_line` to read only the relevant slice. Do NOT call `read_project_file` before you have seen the outline.
>
> **Exception**: fresh code generation in an empty workspace — skip outline, go straight to `write_project_file`.

配一个正例（outline → grep → 精读 → edit）和一个反例（上来就 read 整个文件），比单纯写规则有效。

### 3.6 生成侧命名约定（安全网）

即使模型偷懒跳过 outline，如果生成侧命名稳定，它也能直接猜对关键词。在系统 prompt 的代码生成规则里加一条：

> View IDs use `snake_case` with a type prefix: `btn_*`, `tv_*`, `et_*`, `iv_*`, `sw_*`, `rv_*`, `ll_*`. String resources use `snake_case`. Colors use `snake_case`. Java methods for click handlers use `on<Target>Click` (e.g., `onSubmitClick`).

这条不保证执行，但配合 outline 方案能把退化概率压到很低。零成本，一起做。

## 4. 工具定义

### 4.1 基本信息

- **name**：`grep_project_files`
- **放置位置**：`app/src/main/kotlin/com/vibe/app/feature/agent/tool/GrepProjectFilesTool.kt`
- **注册位置**：`DefaultAgentToolRegistry`
- **description**（给模型看）：
  > Search project files by keyword or regex. Use this AFTER `list_project_files` (to learn symbols) and BEFORE `read_project_file` (to locate the exact line). Returns matching lines with line numbers.

### 4.2 Input Schema

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `pattern` | string | ✅ | — | 搜索词。默认按字面量匹配 |
| `regex` | boolean | ❌ | `false` | 设为 `true` 时按 `java.util.regex.Pattern` 解析 |
| `path` | string | ❌ | `""` | 工作区相对子目录，如 `src/main/java`、`res/values`；缺省搜整个工程 |
| `glob` | string | ❌ | `""` | 文件名通配，支持 `*.java`、`*.xml`、`{*.java,*.xml}`、`**/strings.xml` |
| `case_insensitive` | boolean | ❌ | `false` | 忽略大小写 |
| `output_mode` | enum | ❌ | `content` | `content` \| `files_with_matches` \| `count` |
| `context_lines` | int | ❌ | `0` | 仅 `content` 模式有效，范围 0–3 |
| `max_results` | int | ❌ | `50` | 硬上限 200；仅对 `content` 模式计"匹配行数"，其它模式计"文件数" |

### 4.3 输出格式

采用紧凑文本而非大 JSON 结构，省 token 且符合模型阅读习惯（类 ripgrep）。

**`content` 模式**：

```json
{
  "matches": "src/main/java/.../MainActivity.java:42:    setContentView(R.layout.activity_main);\nsrc/main/java/.../MainActivity.java:58:    Button btn = findViewById(R.id.btn_submit);\nres/values/strings.xml:5:    <string name=\"app_name\">Demo</string>",
  "match_count": 3,
  "file_count": 2,
  "truncated": false
}
```

**`files_with_matches` 模式**：

```json
{
  "files": ["src/main/java/.../MainActivity.java", "res/values/strings.xml"],
  "file_count": 2,
  "truncated": false
}
```

**`count` 模式**：

```json
{
  "counts": "src/main/java/.../MainActivity.java:2\nres/values/strings.xml:1",
  "file_count": 2,
  "truncated": false
}
```

`truncated: true` 时表示命中了 `max_results` 硬上限，模型应收窄条件重查。

## 5. VibeApp 特有约束（实现硬编码）

这些约束不依赖模型自觉，必须写进工具实现：

1. **Sandbox**：所有路径经 `workspace.resolveFile()` 做 canonical 检查，越界即 throw。复用 `DefaultProjectWorkspace.resolveFile` 的现有逻辑，不重复实现。
2. **默认排除目录**：
   - `build/`（构建产物，`listFiles` 已经排除，这里对齐）
   - `.gradle/`、`.idea/`
   - `res/drawable*/`、`res/mipmap*/`、`assets/` 下的二进制（按扩展名，见下）
3. **二进制文件按扩展名跳过**：`png jpg jpeg webp gif bmp ico ttf otf woff zip jar aar dex apk so mp3 mp4 wav pdf`
4. **单文件大小上限**：1 MB。超过就 skip，在结果里不记入，避免 I/O 卡住。
5. **单行长度截断**：匹配行超 500 字符时保留前 500 + `…`，防止 minified XML/JSON 撑爆输出。
6. **总输出上限**：`content` 模式下所有匹配行拼起来不超过 32 KB，超限按 truncated 处理。这是比 `max_results` 更终极的保险。
7. **协程 + 取消**：`withContext(Dispatchers.IO)`，循环内 `ensureActive()`，尊重 agent loop 的取消信号，与现有 `ReadProjectFileTool` 风格一致。
8. **字符编码**：统一 UTF-8，和 `readTextFile` 对齐；遇到解码异常的文件按二进制处理并 skip。

## 6. 实现草图

```kotlin
@Singleton
class GrepProjectFilesTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "grep_project_files",
        description = "...",
        inputSchema = buildJsonObject { /* 见 4.2 */ },
    )

    override suspend fun execute(
        call: AgentToolCall,
        context: AgentToolContext,
    ): AgentToolResult = withContext(Dispatchers.IO) {
        val args = parseArgs(call.arguments)
        val workspace = projectManager.openWorkspace(context.projectId)
        val root = workspace.rootDir.canonicalFile
        val searchRoot = if (args.path.isBlank()) root
            else workspace.resolveFile(args.path) // 会做 sandbox 校验

        val matcher = buildMatcher(args)          // Pattern or literal
        val globMatcher = buildGlobMatcher(args.glob)

        val collector = GrepCollector(
            mode = args.outputMode,
            maxResults = args.maxResults.coerceAtMost(HARD_MAX),
            totalByteCap = TOTAL_BYTE_CAP,
            contextLines = args.contextLines,
        )

        searchRoot.walkTopDown()
            .onEnter { dir -> !isExcludedDir(dir, root) }
            .filter { it.isFile && !isBinary(it) && it.length() <= FILE_SIZE_CAP }
            .filter { globMatcher.matches(it.toRelativeString(root)) }
            .forEach { file ->
                ensureActive()
                scanFile(file, root, matcher, collector)
                if (collector.full) return@forEach
            }

        call.result(collector.toJson())
    }
}
```

关键辅助：

- `isExcludedDir`：检查 `build/`、`.gradle/`、`.idea/`，以及 `res/drawable*`、`res/mipmap*`、`assets/`（后三个仅在默认情况下排除，如果用户显式传了 `path=res/drawable-xxhdpi` 则允许）。
- `buildMatcher`：`regex=false` 时用 `String.indexOf`（字面量）+ 可选 `equals(ignoreCase)`；`regex=true` 时用 `Pattern.compile(pattern, flags)`。字面量路径比正则快一个数量级。
- `buildGlobMatcher`：直接用 `FileSystems.getDefault().getPathMatcher("glob:...")`（API 26+，`minSdk = 29` 可用）。
- `scanFile`：`file.useLines { ... }` 逐行扫描，命中后按 `output_mode` 写入 collector。`context_lines > 0` 时需要维护一个前向 ring buffer + 后向计数器。

## 7. 与现有工具/prompt 的协同

### 7.1 `ReadProjectFileTool` 增加 line range

为了让"先 grep 定位、再精读"真正省 token，`read_project_file` 新增可选参数：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `start_line` | int | `1` | 起始行（1-based, 含） |
| `end_line` | int | `-1` | 结束行（含），`-1` 表示读到文件尾 |

仅对单文件读（`path` 模式）生效；`paths` 批量模式忽略该参数以保持兼容。返回里附带 `total_lines` 和 `range`，让模型知道是否读全。

### 7.2 `list_project_files` 升级 outline

见 §3.2–§3.4。新建 `ProjectOutlineBuilder` 纯函数类，单独测；`ListProjectFilesTool` 只负责调用它并拼 JSON。

### 7.3 `agent-system-prompt.md` 更新

三处改动：

1. **工作流强约束**（§3.5）：修改已有代码的 turn 必须先 `list_project_files`，禁止直接 `read_project_file`
2. **命名约定**（§3.6）：view id、strings、colors、click handler 的命名规则
3. **工具卡片**：`grep_project_files` 的一句话描述 + 一个正例（outline→grep→精读→edit）+ 一个反例（直接 read 整个 MainActivity）

## 8. 测试计划

测试目录：`app/src/test/kotlin/com/vibe/app/feature/agent/tool/`

### 8.1 `GrepProjectFilesToolTest`

1. 字面量匹配 + 大小写
2. 正则模式
3. `path` 子目录限定
4. `glob` 过滤（`*.java`、`**/strings.xml`）
5. Sandbox：`path = "../../etc"` 应抛错
6. 排除目录：`build/` 下的同名匹配不应出现
7. 二进制扩展名跳过
8. 单文件超 1 MB 跳过
9. `max_results` 达到上限后 `truncated = true`
10. 总输出超 32 KB 后 `truncated = true`
11. `context_lines = 2` 时前后行正确输出
12. 三种 `output_mode` 的输出结构
13. 协程取消：mock 一个大工程并在中途 cancel，验证立即退出

### 8.2 `ProjectOutlineBuilderTest`

1. Java：抽取 class 名 + public/protected 方法名，忽略 private
2. Java：方法数 > 20 时截断
3. layout XML：抽取 `@+id/xxx`，id 数 > 30 时截断
4. values XML：抽取 `name="..."`
5. AndroidManifest：抽取 `package` + activity 列表
6. 未知文件类型：只列路径
7. 总 outline > 8 KB 时截断，并标注 `…outline truncated`
8. 空工程：outline 为空字符串

### 8.3 `ReadProjectFileToolTest`（补充）

1. `start_line`/`end_line` 切片行为
2. `end_line = -1` 读到文件尾
3. 越界 line 数应 clamp 而非抛错
4. `paths` 批量模式忽略 line range

### 8.4 Agent-loop smoke test

在一个 fixture 项目里跑一次 `list_project_files`（带 outline）→ `grep_project_files` → `read_project_file`（带 range），确认注册表接入正常。

## 9. 非目标（留待将来）

- **增量索引**：不做。VibeApp 单工程文件数小，全量扫每次都在百毫秒级，索引的复杂度不值得。
- **多行正则**：不支持 `multiline` / `dotall`。Java+XML 场景极少需要跨行匹配，等有真实需求再加。
- **gitignore 尊重**：VibeApp 工作区不是 git 仓库，不存在 `.gitignore`，忽略这个维度。
- **模糊 / 语义搜索**：超出本工具职责。如果未来要做 semantic search，应该是独立的 `search_project_semantic` 工具。
- **真正的 Java 解析器**：outline 用正则就够了。引入 JavaParser/AST 会拖大 APK 且对生成代码收益有限。

## 10. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 大工程扫描耗时 | 文件数小 + 大小上限 1 MB + 协程可取消；必要时加工具层面的软超时（例如 3 秒后 truncate 返回） |
| 正则回溯爆炸 | `regex = false` 为默认；若设置 `regex = true`，compile 时不做额外保护，但单文件 1 MB / 总输出 32 KB 的上限能兜底 |
| outline 正则误判 | 方法名抽取只作为"候选关键词"给模型用，即使漏掉或误抓几个，grep 仍能找到真实位置；不影响正确性，只影响召回 |
| 模型仍然习惯先 `read_project_file` | 靠 system prompt 强约束（§3.5） + outline 默认开启 + `read_project_file` 的 description 里加 "For locating code, call `list_project_files` first" |
| 输出格式变更破坏兼容 | 首版尽量对齐 ripgrep 的 `file:line:text` 约定，降低将来改动概率；`include_outline = false` 保留旧行为 |

## 11. 交付清单

- [ ] `GrepProjectFilesTool.kt`（含 input schema、实现、内部辅助）
- [ ] `ProjectOutlineBuilder.kt`（纯函数，outline 抽取核心）
- [ ] `ListProjectFilesTool` 接入 outline + `include_outline` 参数
- [ ] `ReadProjectFileTool` 增加 `start_line` / `end_line` 参数
- [ ] `DefaultAgentToolRegistry` 注册 `GrepProjectFilesTool`
- [ ] `agent-system-prompt.md`：工作流约束 + 命名约定 + 新工具卡片
- [ ] `GrepProjectFilesToolTest`
- [ ] `ProjectOutlineBuilderTest`
- [ ] `ReadProjectFileToolTest` 补充用例
- [ ] Agent-loop smoke test
- [ ] 在 `docs/function-calling-agent-loop.md` 的工具表里补一行
- [ ] 手动在真机上跑一次：生成一个 Java/XML 项目 → 让 agent 走 outline→grep→range-read→edit → 编译通过
