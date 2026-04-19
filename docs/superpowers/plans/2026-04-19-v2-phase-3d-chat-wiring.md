# v2.0 Phase 3d: Chat-side wiring for v2 (Kotlin + Compose) projects

**Goal:** make the v2 agent toolchain delivered in Phase 3c end-to-end
usable from the existing chat UI, so a user can type "make a counter
app" → the agent calls `create_compose_project` + `assemble_debug_v2`
+ `install_apk_v2` → a real APK lands on screen.

**Architecture:** instead of building a parallel "Compose mode" UX
stack, Phase 3d makes the existing chat plumbing v2-aware in two small
places:
  1. **`ProjectDao.getProjectByChatId`** now sorts `engine ASC,
     created_at DESC LIMIT 1`. Once `create_compose_project` adds a
     GRADLE_COMPOSE row to the chat (alongside the auto-created v1
     LEGACY one from `createNewProject`), the v2 row wins from this
     query forward — engine and createdAt naturally favor v2.
  2. **`ChatViewModel.observeAgentSessionState`** re-fetches the
     project after each agent turn finishes (it already re-reads name
     for `rename_project`; now it also updates `_currentProjectId`).
     So the very first turn that calls `create_compose_project` is
     followed by the v2 projectId being live for subsequent turns —
     `assemble_debug_v2` / `install_apk_v2` find the right project.

Net effect: no new UI affordance, no new ProjectManager API, no
Compose-mode toggle. The existing "New Project" button + chat flow
works for both v1 and v2 because the agent picks the right toolset
based on user intent (the system prompt update from Phase 3c steers
it).

## Scope

| In | Out (deferred) |
|---|---|
| `getProjectByChatId` engine-aware ordering | Dedicated "New Compose Project" button in HomeScreen |
| ChatViewModel re-fetches `_currentProjectId` after each turn | Compose-mode chat-creation flow (skip v1 init when user wants v2) |
| Manual e2e verification on emulator | Build progress streaming UI (assemble_debug_v2 still blocks 5–10 min on cold cache) |
| | Cancel-mid-build affordance |
| | Cleanup of orphaned v1 workspace dirs when v2 is created in same chat |

## Executed changes

| File | Change |
|---|---|
| `app/src/main/kotlin/com/vibe/app/data/database/dao/ProjectDao.kt` | `getProjectByChatId` sorts `engine ASC, created_at DESC LIMIT 1`. KDoc added explaining the multi-project-per-chat scenario. |
| `app/src/main/kotlin/com/vibe/app/presentation/ui/chat/ChatViewModel.kt` | After each agent session completes, refresh `_currentProjectId` (alongside the existing `_projectName` refresh). |

## Exit criteria

- [x] Query change compiles + the `:app:assembleDebug` regression stays green.
- [x] Existing v1 chats (only one Project per chatId) keep returning that one Project (single-row SELECT with LIMIT 1 is identical in shape).
- [x] Multi-project chats prefer GRADLE_COMPOSE — verified by inspection of the SQL (engine column sorts G < L alphabetically).
- [ ] Manual e2e: emulator + dev server up, click "New Project" in Home → chat says "make a Compose counter app" → agent calls create_compose_project → assemble_debug_v2 → install_apk_v2 → counter APK on screen. **(USER, please run.)**

## Exit Log (2026-04-19)

**Validated by:**
- `:app:kspDebugKotlin` + `:app:assembleDebug` — green.
- Code inspection of the SQL change: backwards-compatible for chats with one Project, deterministic preference for v2 in multi-project chats.

**Deliberately deferred:**
- **Dedicated "New Compose Project" entry point.** Could be a Home-screen
  button that creates a chat without auto-init'ing a v1 project, so the
  first agent action is always `create_compose_project`. Today the user
  goes through the existing flow (which auto-creates a v1 init project
  per `createProject(...)`); the v1 workspace is harmlessly orphaned on
  disk once the agent creates a v2 project in the same chat.
  Add when wasted disk becomes user-visible.
- **Build progress streaming.** `assemble_debug_v2` still calls
  `runBuild(...).toList()` and blocks until BuildFinish. The chat UI shows
  "Calling assemble_debug_v2..." with no progress indicator for 5–10
  minutes on cold cache. Not blocking for an MVP; user has to be patient.
  When we wire it, the natural shape is to emit BuildProgress events as
  AgentToolStatus updates so the chat shows "compiling Kotlin... linking
  resources... dexing..." in real time.
- **Cancel mid-build.** No way to abort a running assemble. Surface as a
  cancel button on the running tool call.
- **Orphaned v1 workspace cleanup.** When `create_compose_project` runs on
  a chat that already has a v1 init project, we leave the v1 workspace
  files orphaned under `filesDir/projects/{v1Id}/`. Add a cleanup pass.

**Phase 3d is complete on the code side.** End-to-end manual verification
is the remaining work and requires the user to drive a chat session on
device (the dev tools can't simulate "user types a sentence" cleanly).
After verification + any bug fixes that surface, Phase 3 is fully done
and Phase 4 (build-error diagnostic ingest pipeline + agent system
prompt rewrite) is unblocked.
