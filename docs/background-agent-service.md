# Background Agent Service Design

> Design document for decoupling the agent loop from ChatScreen lifecycle, enabling background execution, foreground service protection, completion notifications, and safe concurrent builds.

## Problem Statement

Currently, the agent loop runs entirely within `ChatViewModel.viewModelScope`. When the user navigates away from `ChatScreen` or the app enters background, `viewModelScope` is cancelled, causing:

1. All in-flight model requests abort immediately
2. Partial tool execution results are lost
3. Build pipelines triggered by the agent are interrupted
4. No mechanism exists to notify the user of completion

Additionally, Android's background execution limits mean the system may kill the app process within seconds of entering background, making coroutine-only solutions insufficient.

## Goals

1. **Agent loop survives ChatScreen exit** - navigating to Home or Settings does not cancel work
2. **Agent loop survives app backgrounding** - a foreground service prevents process death
3. **Completion notification** - user receives a system notification when the agent finishes
4. **Concurrent task safety** - multiple chat sessions can run agent loops simultaneously without data corruption
5. **Minimal architectural disruption** - reuse existing singletons, event types, and DI graph

## Non-Goals (v1)

- Surviving a force-stop or device reboot (would require WorkManager + resumable state)
- Queueing offline requests for later execution
- Detailed per-step progress in the notification (v1 shows indeterminate progress)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│                                                         │
│  ChatScreen ──observes──▶ ChatViewModel                 │
│                               │                         │
│                          delegates to                   │
│                               ▼                         │
│                      AgentSessionManager ◀──inject──┐   │
│                        (Application-scoped)         │   │
│                               │                     │   │
│                     ┌─────────┴─────────┐           │   │
│                     ▼                   ▼           │   │
│              AgentSession         AgentSession      │   │
│              (chatId=1)           (chatId=2)        │   │
│                  │                    │             │   │
│                  ▼                    ▼             │   │
│          AgentLoopCoordinator  AgentLoopCoordinator │   │
│                  │                    │             │   │
│                  ▼                    ▼             │   │
│            BuildPipeline ◀── BuildMutex (serialize) │   │
│                                                     │   │
├─────────────────────────────────────────────────────│───┤
│                   Service Layer                     │   │
│                                                     │   │
│  AgentForegroundService ◀── binds/starts ───────────┘   │
│     • Foreground notification (ongoing)                  │
│     • Completion notification                            │
│     • Lifecycle tied to active sessions                  │
└─────────────────────────────────────────────────────────┘
```

---

## Component Design

### 1. AgentSession

A lightweight data holder representing one active agent task bound to a specific chat.

```kotlin
data class AgentSession(
    val chatId: Int,
    val projectId: String,
    val job: Job,                          // Coroutine job for cancellation
    val status: StateFlow<AgentSessionStatus>,
)

enum class AgentSessionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
```

### 2. AgentSessionManager (new, Application-scoped Singleton)

Central orchestrator that owns agent loop execution in a **process-scoped** `CoroutineScope`, independent of any ViewModel.

```kotlin
@Singleton
class AgentSessionManager @Inject constructor(
    private val agentLoopCoordinator: AgentLoopCoordinator,
    private val agentToolRegistry: AgentToolRegistry,
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val buildMutex: BuildMutex,
    private val notificationHelper: AgentNotificationHelper,
) {
    // Process-scoped — survives ViewModel destruction
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Active sessions indexed by chatId
    private val _sessions = MutableStateFlow<Map<Int, AgentSession>>(emptyMap())
    val sessions: StateFlow<Map<Int, AgentSession>> = _sessions.asStateFlow()

    // Per-session event stream for UI observation
    private val eventFlows = ConcurrentHashMap<Int, MutableSharedFlow<AgentLoopEvent>>()

    fun startSession(chatId: Int, request: AgentLoopRequest): Boolean
    fun stopSession(chatId: Int)
    fun observeEvents(chatId: Int): Flow<AgentLoopEvent>
    fun getSessionStatus(chatId: Int): StateFlow<AgentSessionStatus>?
    val hasActiveSessions: StateFlow<Boolean>
}
```

**Key behaviors:**

- `startSession()` launches the agent loop in `scope`, NOT `viewModelScope`
- Events are emitted into a per-chat `MutableSharedFlow` with `replay = 128` so the UI can reconnect and catch up
- When all sessions finish, signals the foreground service to stop
- On completion/failure, triggers notification via `AgentNotificationHelper`
- Persists final chat state to Room via `chatRepository`

### 3. AgentForegroundService

A bound + started `ForegroundService` that keeps the process alive during agent execution.

```kotlin
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject lateinit var sessionManager: AgentSessionManager
    @Inject lateinit var notificationHelper: AgentNotificationHelper

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notificationHelper.buildOngoingNotification(activeCount))
        observeSessionsAndAutoStop()
        return START_NOT_STICKY  // Don't restart if killed — work is not resumable
    }

    private fun observeSessionsAndAutoStop() {
        lifecycleScope.launch {
            sessionManager.hasActiveSessions.collect { hasActive ->
                if (!hasActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }
}
```

**Lifecycle contract:**

| Event | Action |
|-------|--------|
| First agent session starts | `startForegroundService()` called by `AgentSessionManager` |
| Session count changes | Update notification text ("N tasks running") |
| Last session finishes | Service calls `stopSelf()` |
| User force-stops app | Process dies, sessions lost (acceptable for v1) |

**Service type:** `foregroundServiceType="shortService"` (API 34+) or `"dataSync"`. Since our work is network I/O + local compilation, `dataSync` is the best fit.

### 4. AgentNotificationHelper

Handles all notification construction and channel management.

```kotlin
@Singleton
class AgentNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ONGOING = "agent_ongoing"
        const val CHANNEL_RESULT = "agent_result"
        const val ONGOING_NOTIFICATION_ID = 1001
    }

    fun createChannels() { /* Called from Application.onCreate() */ }

    fun buildOngoingNotification(activeCount: Int): Notification {
        // Low-priority, ongoing, non-dismissible
        // Title: "VibeApp - Working"
        // Text: "$activeCount task(s) in progress"
        // Action: tap opens app, "Cancel All" action button
    }

    fun showCompletionNotification(chatId: Int, projectName: String, success: Boolean) {
        // High-priority, auto-cancel
        // Success: "Project '$projectName' is ready! Tap to view."
        // Failure: "Build failed for '$projectName'. Tap to see errors."
        // PendingIntent: deep-link to ChatScreen for this chatId
    }
}
```

**Notification channels:**

| Channel | ID | Importance | Purpose |
|---------|----|------------|---------|
| Ongoing Work | `agent_ongoing` | LOW | Foreground service (no sound/vibration) |
| Task Result | `agent_result` | HIGH | Completion/failure alerts |

### 5. BuildMutex (new, Singleton)

Serializes build pipeline execution to avoid AAPT2 concurrency corruption.

```kotlin
@Singleton
class BuildMutex @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withBuildLock(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
```

**Why a Mutex instead of fixing AAPT2Jni:**

- `Aapt2Jni.java` is in `build-tools/` which is treated as a prebuilt input (per CLAUDE.md)
- The JNI singleton pattern with `static` fields cannot be safely patched without risk
- A coroutine `Mutex` is zero-overhead when uncontested and correctly serializes concurrent builds
- Multiple agent loops can run model inference in parallel; only the build step is serialized

**Integration point:** The `RunBuildPipelineTool` wraps its `buildProject()` call in `buildMutex.withBuildLock { ... }`.

### 6. ChatViewModel Changes

The ViewModel becomes a **thin observation layer** that delegates execution to `AgentSessionManager`.

```kotlin
// Before (current)
private fun completeChat(turnState: ActiveTurnState) {
    val job = viewModelScope.launch {
        runAgentLoop(...)  // Dies with ViewModel
    }
    responseJobs.add(job)
}

// After (proposed)
private fun completeChat(turnState: ActiveTurnState) {
    sessionManager.startSession(chatRoomId, buildAgentRequest())

    // Observe events — reconnectable even after recomposition
    viewModelScope.launch {
        sessionManager.observeEvents(chatRoomId).collect { event ->
            handleAgentEvent(event)  // Update UI state flows
        }
    }
}
```

**When user re-enters ChatScreen:**

1. `ChatViewModel.init` checks `sessionManager.getSessionStatus(chatId)`
2. If `RUNNING`, reconnects to `observeEvents(chatId)` and resumes UI updates
3. `SharedFlow(replay=128)` provides recent events the UI missed

---

## Concurrency Analysis

### What can safely run in parallel

| Component | Concurrent-safe? | Notes |
|-----------|:-:|-------|
| Model inference (network I/O) | Yes | Each request is independent HTTP call |
| Agent tool: ReadFile, WriteFile, ListFiles | Yes | Per-project workspace isolation |
| Agent tool: RenameProject, UpdateIcon | Yes | Per-project workspace isolation |
| Agent tool: RunBuild | **No** | AAPT2Jni shared static state |
| ConversationContextManager | Yes | Stateless, operates on input data |
| Chat persistence (Room) | Yes | Room handles threading internally |

### Serialization strategy

```
Agent Session A (chat 1)          Agent Session B (chat 2)
    │                                  │
    ├─ model inference ────────────────├─ model inference      (parallel OK)
    ├─ tool: write file ───────────────├─ tool: read file      (parallel OK)
    ├─ tool: RunBuild ─┐               │
    │                  │ BuildMutex     │
    │                  │ (locked)       ├─ tool: RunBuild ─┐
    │                  │               │                   │ (waits)
    ├─ build done ─────┘               │                   │
    │                                  ├─ build done ──────┘
    ▼                                  ▼
```

### Risk: Agent loop timeout during mutex wait

If Session B's build waits for Session A's build, the agent loop iteration timer does not tick (it's a suspend point). This is acceptable because:
- Build operations themselves have bounded duration (typically < 60s)
- The 5-minute HTTP timeout is per-request, not per-tool
- The agent's `maxIterations` limit counts loop turns, not wall time

---

## Required Manifest Changes

```xml
<!-- New permissions -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- Service declaration -->
<service
    android:name=".feature.agent.service.AgentForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

---

## Notification Permission Flow

Android 13+ (API 33+) requires runtime permission for `POST_NOTIFICATIONS`. Since `minSdk = 29`, we need to handle both cases:

| API Level | Behavior |
|-----------|----------|
| 29-32 | Notifications work without runtime permission |
| 33+ | Must request `POST_NOTIFICATIONS` at runtime |

**When to request:** On first agent task start, before launching the foreground service. If denied:
- Foreground service still works (notification is shown but may be hidden by system)
- Completion notifications won't appear
- Show a one-time in-app banner: "Enable notifications to see task results"

---

## Data Flow: Complete Lifecycle

```
1. User sends message in ChatScreen
   │
2. ChatViewModel.askQuestion()
   │
3. ChatViewModel.completeChat()
   ├─ sessionManager.startSession(chatId, request)
   │   ├─ Creates AgentSession with process-scoped Job
   │   ├─ Starts AgentForegroundService (if not running)
   │   └─ Launches agent loop coroutine
   │
   └─ viewModelScope.launch { sessionManager.observeEvents(chatId).collect { ... } }
       └─ Updates UI StateFlows (messages, loading, thoughts)

4. User navigates away from ChatScreen
   ├─ ChatViewModel.onCleared() → viewModelScope cancelled
   │   └─ Event observation stops, BUT agent loop continues in sessionManager.scope
   │
   └─ AgentForegroundService keeps process alive

5. Agent loop completes
   ├─ sessionManager saves chat to Room
   ├─ notificationHelper.showCompletionNotification(chatId, projectName, success)
   ├─ Session status → COMPLETED/FAILED
   └─ If no more active sessions → service.stopSelf()

6. User taps notification or re-enters ChatScreen
   ├─ ChatViewModel.init reads persisted messages from Room
   ├─ Checks sessionManager for any still-running session
   └─ Reconnects to event stream if session is active
```

---

## File & Package Structure

```
app/src/main/kotlin/com/vibe/app/
├── feature/
│   └── agent/
│       ├── loop/          # Existing — no changes
│       ├── tool/          # Existing — RunBuildPipelineTool adds BuildMutex
│       └── service/       # NEW
│           ├── AgentSession.kt
│           ├── AgentSessionManager.kt
│           ├── AgentForegroundService.kt
│           ├── AgentNotificationHelper.kt
│           └── BuildMutex.kt
├── di/
│   └── AgentModule.kt    # Add new bindings
└── presentation/
    └── ui/chat/
        └── ChatViewModel.kt  # Refactor to delegate to SessionManager
```

---

## Implementation Plan

### Phase 1: Infrastructure (no behavior change)

1. Create `AgentNotificationHelper` with channel setup
2. Create `BuildMutex` and inject into `RunBuildPipelineTool`
3. Create `AgentForegroundService` skeleton
4. Add manifest permissions and service declaration
5. Call `notificationHelper.createChannels()` from `GPTMobileApp.onCreate()`

### Phase 2: AgentSessionManager

1. Implement `AgentSessionManager` with process-scoped coroutine
2. Implement `startSession()`, `stopSession()`, `observeEvents()`
3. Wire completion → notification flow
4. Wire session count → foreground service start/stop
5. Add to Hilt DI graph

### Phase 3: ChatViewModel Migration

1. Refactor `completeChat()` to delegate to `AgentSessionManager`
2. Refactor `stopResponding()` to call `sessionManager.stopSession()`
3. Add reconnection logic in `init` block
4. Update `observeStateChanges()` auto-save to work with new event source
5. Keep build progress observation working through session events

### Phase 4: Polish & Edge Cases

1. Runtime notification permission request (API 33+)
2. "Cancel All" action in ongoing notification
3. Deep-link from completion notification to correct ChatScreen
4. Handle rapid start/stop/restart of sessions
5. Diagnostic logger integration with new lifecycle

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| User starts task, leaves, returns while running | Reconnects to SharedFlow, catches up via replay buffer |
| User starts task, leaves, returns after completion | Loads final state from Room; notification already shown |
| Two chats run agents simultaneously | Both run in parallel; builds serialized via BuildMutex |
| User cancels from notification | `stopSession()` cancels coroutine Job; partial state saved |
| Notification permission denied | Service still runs; completion shown as in-app state on next visit |
| App killed by system despite foreground service | Rare but possible under extreme memory pressure; state lost (v1 acceptable) |
| Agent loop fails mid-tool | Error captured by existing `LoopFailed` event; notification shows failure |
| User starts new message while previous is still running | `stopSession()` for old, `startSession()` for new (same as current behavior) |

---

## Open Questions for v2

1. **Resumable sessions**: Should we persist agent loop state to Room so work can resume after process death?
2. **WorkManager fallback**: For very long builds, should we schedule a WorkManager task as a backup?
3. **Per-step notification progress**: Show "Compiling...", "Signing..." in the ongoing notification?
4. **Multiple simultaneous builds**: Fix AAPT2Jni thread safety instead of serializing with Mutex? (Requires modifying prebuilt inputs)
5. **Notification grouping**: If 5+ tasks complete, group notifications?
