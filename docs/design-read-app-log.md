# Design: `read_app_log` Tool & Plugin Runtime Logging

## 1. Overview

When a user builds and runs a generated app (plugin), there is currently no way for the AI agent to read runtime logs. If the app crashes, behaves unexpectedly, or the user reports a bug, the AI has no runtime context to help debug.

This design introduces:
1. **`AppLogger.java`** — a template-side logging utility the AI can use in generated code
2. **Host-side crash/ANR capture** — PluginContainerActivity captures plugin failures
3. **`read_app_log` tool** — allows the AI agent to read these logs during conversation

## 2. Log Storage Architecture

### 2.1 Directory Layout

```
files/projects/{projectId}/
├── app/           # existing workspace (source code, build output)
└── logs/          # NEW — runtime logs
    ├── app.log        # application runtime log (written by AppLogger)
    ├── app.log.1      # rotated backup
    ├── crash.log      # crash stack traces
    └── anr.log        # ANR traces
```

Each project has its own `logs/` directory, parallel to the existing `app/` workspace directory. This naturally supports multi-project isolation — logs never mix between projects.

### 2.2 Why Parallel to `app/`, Not Inside It

- `app/` is the build workspace; `clean_build_cache` deletes `app/build/`. Logs must survive cache cleaning.
- `list_project_files` walks `app/`. Log files should not pollute the project file listing.
- Separating concerns: source code vs. runtime artifacts.

## 3. Template Changes

### 3.1 New File: `AppLogger.java`

**Location in template:** `$packagename/AppLogger.java`

**Location in project:** `src/main/java/{package_path}/AppLogger.java`

```java
package $packagename;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple file-based logger for runtime diagnostics.
 * Logs are written to the project's logs/ directory and can be
 * read by the AI agent via the read_app_log tool.
 *
 * Usage:
 *   AppLogger.d("MyActivity", "Button clicked, loading data...");
 *   AppLogger.e("MyActivity", "Failed to parse JSON", exception);
 */
public class AppLogger {

    private static final long MAX_LOG_SIZE = 256 * 1024; // 256 KB
    private static final long MAX_CRASH_SIZE = 128 * 1024; // 128 KB
    private static final Object LOCK = new Object();
    private static File logDir;

    /** Called once at startup. Safe to call multiple times. */
    public static void init(File dir) {
        synchronized (LOCK) {
            logDir = dir;
            if (dir != null) {
                dir.mkdirs();
            }
        }
    }

    public static void d(String tag, String message) {
        writeLog("D", tag, message, null);
    }

    public static void i(String tag, String message) {
        writeLog("I", tag, message, null);
    }

    public static void w(String tag, String message) {
        writeLog("W", tag, message, null);
    }

    public static void e(String tag, String message) {
        writeLog("E", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        writeLog("E", tag, message, throwable);
    }

    /** Write a crash stack trace to crash.log. */
    public static void crash(Throwable throwable) {
        synchronized (LOCK) {
            if (logDir == null) return;
            try {
                File crashFile = new File(logDir, "crash.log");
                rotateIfNeeded(crashFile, MAX_CRASH_SIZE);
                FileWriter fw = new FileWriter(crashFile, true);
                fw.write("--- CRASH " + timestamp() + " ---\n");
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                fw.write(sw.toString());
                fw.write("\n");
                fw.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeLog(String level, String tag, String message, Throwable t) {
        synchronized (LOCK) {
            if (logDir == null) return;
            try {
                File logFile = new File(logDir, "app.log");
                rotateIfNeeded(logFile, MAX_LOG_SIZE);
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(timestamp() + " " + level + "/" + tag + ": " + message + "\n");
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    fw.write(sw.toString());
                }
                fw.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void rotateIfNeeded(File file, long maxSize) {
        if (file.exists() && file.length() > maxSize) {
            File backup = new File(file.getPath() + ".1");
            backup.delete();
            file.renameTo(backup);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
```

### 3.2 Modify `CrashHandlerApp.java`

Add `AppLogger` initialization and crash log writing:

```java
@Override
public void onCreate() {
    super.onCreate();

    // Initialize AppLogger — logDir set by host (plugin mode) or fallback to local
    // In plugin mode, PluginContainerActivity calls AppLogger.init() before Activity.onCreate()
    // In standalone mode, use app-local directory
    if (AppLogger.getLogDir() == null) {
        AppLogger.init(new File(getFilesDir(), "logs"));
    }

    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            // Write to crash.log (readable by AI agent)
            AppLogger.crash(e);

            // Also save to SharedPreferences (for crash dialog on next launch)
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CRASH_LOG, sw.toString())
                    .commit();

            Process.killProcess(Process.myPid());
            System.exit(1);
        }
    });
    // ... rest unchanged
}
```

Add a `getLogDir()` static method to `AppLogger` so CrashHandlerApp can check initialization state.

### 3.3 Template File Registration

`AppLogger.java` must be:
1. Added to `app/src/main/assets/templates/EmptyActivity/app/src/main/java/$packagename/AppLogger.java`
2. Listed in the system prompt's template structure section
3. Marked as a **DO NOT modify/delete** file (same as CrashHandlerApp)

## 4. Host-Side Changes

### 4.1 PluginContainerActivity — Initialize AppLogger in Plugin Mode

After loading the plugin class, initialize `AppLogger` via reflection so logs go to the correct project directory:

```kotlin
// In PluginContainerActivity.onCreate(), after pluginClassLoader is ready:

val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
if (projectId != null) {
    val logDir = File(filesDir, "projects/$projectId/logs")
    logDir.mkdirs()
    try {
        // Find AppLogger in the plugin's ClassLoader and initialize it
        val packageName = intent.getStringExtra(EXTRA_MAIN_CLASS)
            ?.substringBeforeLast('.') ?: ""
        val loggerClass = pluginClassLoader!!.loadClass("$packageName.AppLogger")
        loggerClass.getMethod("init", File::class.java).invoke(null, logDir)
    } catch (e: ClassNotFoundException) {
        Log.d(TAG, "Plugin has no AppLogger, skipping log init")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to init AppLogger for plugin", e)
    }
}
```

### 4.2 PluginContainerActivity — Capture Plugin Crashes

Wrap plugin lifecycle calls in try-catch to capture crashes to crash.log:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ... existing plugin loading code ...
    try {
        instance.performCreate(savedInstanceState)
    } catch (e: Exception) {
        writeCrashLog(projectId, e)
        finish()
    }
}

override fun onResume() {
    super.onResume()
    try {
        pluginActivity?.performResume()
    } catch (e: Exception) {
        writeCrashLog(projectId, e)
        finish()
    }
}

private fun writeCrashLog(projectId: String?, throwable: Throwable) {
    if (projectId == null) return
    val logDir = File(filesDir, "projects/$projectId/logs")
    logDir.mkdirs()
    val crashFile = File(logDir, "crash.log")
    crashFile.appendText(
        "--- CRASH ${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(java.util.Date())} ---\n" +
        Log.getStackTraceString(throwable) + "\n"
    )
}
```

### 4.3 PluginManager — Pass projectId via Intent

```kotlin
fun launchPlugin(apkPath: String, packageName: String, projectId: String = apkPath) {
    // ... existing code ...
    val intent = Intent(context, slotActivities[slotIndex]).apply {
        putExtra(PluginContainerActivity.EXTRA_APK_PATH, apkPath)
        putExtra(PluginContainerActivity.EXTRA_MAIN_CLASS, mainClassName)
        putExtra(PluginContainerActivity.EXTRA_PLUGIN_LABEL, packageName.substringAfterLast('.'))
        putExtra(PluginContainerActivity.EXTRA_SLOT_INDEX, slotIndex)
        putExtra(PluginContainerActivity.EXTRA_PROJECT_ID, projectId)  // NEW
        // ... flags ...
    }
}
```

Add constant: `const val EXTRA_PROJECT_ID = "plugin_project_id"`

### 4.4 ANR Detection (Lightweight)

Add a simple main-thread watchdog in `PluginContainerActivity`:

```kotlin
private var anrWatchdog: Thread? = null

private fun startAnrWatchdog(projectId: String) {
    anrWatchdog = Thread {
        while (!Thread.interrupted()) {
            val responded = CountDownLatch(1)
            runOnUiThread { responded.countDown() }
            try {
                // If main thread doesn't respond in 5 seconds, log ANR
                if (!responded.await(5, TimeUnit.SECONDS)) {
                    writeAnrLog(projectId)
                }
                Thread.sleep(3000) // Check every 3 seconds
            } catch (e: InterruptedException) {
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "anr-watchdog-$projectId"
        start()
    }
}

private fun writeAnrLog(projectId: String) {
    val logDir = File(filesDir, "projects/$projectId/logs")
    logDir.mkdirs()
    val anrFile = File(logDir, "anr.log")
    val sb = StringBuilder()
    sb.append("--- ANR DETECTED ${SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date())} ---\n")
    sb.append("Main thread stack trace:\n")
    for (elem in android.os.Looper.getMainLooper().thread.stackTrace) {
        sb.append("  at $elem\n")
    }
    sb.append("\n")
    anrFile.appendText(sb.toString())
}
```

Start the watchdog in `onCreate()` after plugin load, stop in `onDestroy()`.

## 5. `read_app_log` Tool Design

### 5.1 Tool Definition

```kotlin
private const val READ_APP_LOG = "read_app_log"

class ReadAppLogTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {
    override val definition = AgentToolDefinition(
        name = READ_APP_LOG,
        description = "Read runtime logs from the generated app. " +
            "Returns app logs, crash logs, or ANR logs produced during the app's execution.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("log_type", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("app"))
                        add(JsonPrimitive("crash"))
                        add(JsonPrimitive("anr"))
                        add(JsonPrimitive("all"))
                    })
                    put("description", JsonPrimitive(
                        "Type of log to read: " +
                        "'app' for runtime logs, " +
                        "'crash' for crash stack traces, " +
                        "'anr' for ANR traces, " +
                        "'all' for everything."
                    ))
                })
                put("tail", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(
                        "Number of most recent lines to return. Defaults to 200. " +
                        "Use a smaller value to reduce token usage."
                    ))
                })
            })
            put("required", buildJsonArray { })
        },
    )
}
```

### 5.2 Execute Logic

```kotlin
override suspend fun execute(
    call: AgentToolCall,
    context: AgentToolContext,
): AgentToolResult = withContext(Dispatchers.IO) {
    val logType = call.arguments.optString("log_type") ?: "all"
    val tail = call.arguments.optInt("tail") ?: 200
    val logDir = File(projectLogDir(context.projectId), "logs")

    val result = buildJsonObject {
        if (logType == "app" || logType == "all") {
            put("app_log", readTail(File(logDir, "app.log"), tail))
        }
        if (logType == "crash" || logType == "all") {
            put("crash_log", readTail(File(logDir, "crash.log"), tail))
        }
        if (logType == "anr" || logType == "all") {
            put("anr_log", readTail(File(logDir, "anr.log"), tail))
        }
        put("log_dir_exists", JsonPrimitive(logDir.exists()))
    }

    AgentToolResult(
        toolCallId = call.id,
        toolName = call.name,
        output = result,
    )
}
```

### 5.3 Output Format

```json
{
  "app_log": "03-29 14:02:31.123 D/MainActivity: onCreate called\n03-29 14:02:31.456 D/DataLoader: Loading 42 items...\n...",
  "crash_log": "--- CRASH 03-29 14:03:12.789 ---\njava.lang.NullPointerException: ...\n  at com.vibe.generated.p20260329.MainActivity.onClick(MainActivity.java:45)\n...",
  "anr_log": "",
  "log_dir_exists": true
}
```

Empty string for log types with no data. `log_dir_exists: false` if the app has never been run.

## 6. Log Size Limits & Cleanup

| File | Max Size | Rotation Strategy |
|------|----------|-------------------|
| `app.log` | 256 KB | Rotate to `app.log.1`, keep 1 backup |
| `crash.log` | 128 KB | Rotate to `crash.log.1`, keep 1 backup |
| `anr.log` | 128 KB | Rotate to `anr.log.1`, keep 1 backup |

**Rotation**: When a log file exceeds its max size, rename it to `.1` (deleting any existing `.1`), then start a fresh file.

**Project deletion**: Logs are automatically cleaned up when a project is deleted, since the entire `projects/{projectId}/` directory is removed.

**`clean_build_cache` does NOT touch logs** — logs live outside the `app/` directory.

**Optional: `clear_app_log` parameter**: The `read_app_log` tool could accept an optional `clear: true` parameter to delete logs after reading, useful before a fresh test run. Alternatively, the AI can instruct the user to re-run the app, and old logs are distinguishable by timestamps.

## 7. System Prompt Additions

Add to `agent-system-prompt.md`:

```markdown
## Runtime Logging

The template includes `AppLogger.java` — a file-based logger for runtime diagnostics.
When the user runs the app and reports issues, use `read_app_log` to get logs.

### How to add logging in generated code:
- Import: `import {PACKAGE_NAME}.AppLogger;`
- Debug: `AppLogger.d("TAG", "message");`
- Error: `AppLogger.e("TAG", "message", exception);`
- Log key events: Activity lifecycle, button clicks, data loading, network results.
- Do NOT log sensitive data (passwords, tokens).
- Do NOT modify or delete AppLogger.java or CrashHandlerApp.java.

### Log types available via read_app_log:
- `app` — runtime logs written by AppLogger.d/i/w/e
- `crash` — uncaught exception stack traces (captured automatically)
- `anr` — Application Not Responding traces (captured automatically)
- `all` — everything (default)

### Debugging workflow:
When the user reports the app crashed or has a bug:
1. Call `read_app_log` with `log_type: "crash"` (or `"all"`) to see what happened.
2. Analyze the stack trace or log output.
3. Fix the code, rebuild, and ask the user to test again.
```

Update the template file listing:

```markdown
## Template Project Structure
Default files:
- src/main/java/{{PACKAGE_PATH}}/MainActivity.java
- src/main/java/{{PACKAGE_PATH}}/CrashHandlerApp.java (DO NOT modify)
- src/main/java/{{PACKAGE_PATH}}/AppLogger.java (DO NOT modify)
- ...
```

## 8. Multi-Project Support

Each project has a completely isolated log directory:

```
files/projects/20260329/logs/    ← project A logs
files/projects/20260329_1/logs/  ← project B logs
```

The `read_app_log` tool uses `context.projectId` (from `AgentToolContext`) to locate the correct log directory. No cross-project log access is possible.

When the user switches between conversations/projects, each conversation's AI agent only sees logs for its own project.

## 9. i18n

Add to all `strings.xml` locale files:

```xml
<string name="tool_name_read_app_log">Read app log</string>
```

With appropriate translations per language (e.g., ZH: "读取应用日志", JA: "アプリログ読み取り", etc.)

## 10. Implementation Checklist

1. **Template**: Create `AppLogger.java` in template assets
2. **Template**: Modify `CrashHandlerApp.java` — integrate AppLogger for crash writing
3. **Host**: Modify `PluginManager` — pass `projectId` via Intent extra
4. **Host**: Modify `PluginContainerActivity` — init AppLogger via reflection, wrap lifecycle in try-catch, add ANR watchdog
5. **Tool**: Implement `ReadAppLogTool` in `ProjectTools.kt`
6. **Tool**: Register in `DefaultAgentToolRegistry`
7. **Prompt**: Update `agent-system-prompt.md` with logging guidelines
8. **i18n**: Add `tool_name_read_app_log` to all locale `strings.xml`
9. **Workspace**: Update `ProjectInitializer.customizeProjectWorkspace()` to handle `$packagename` in AppLogger.java (already handled by existing glob replace logic)
