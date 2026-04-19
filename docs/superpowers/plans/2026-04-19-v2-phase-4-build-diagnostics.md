# v2.0 Phase 4: Build-error diagnostic ingest pipeline

**Goal:** when `assemble_debug_v2` fails, the agent sees a clean,
deduped, sorted list of actionable Kotlin / AAPT2 errors with source
snippets — not a 1000-line raw Gradle dump. Foundation for "agent
fixes its own bug in 2-3 turns" UX (the spec's headline Phase 3+4
acceptance scenario).

**Architecture:** small pure-Kotlin pipeline in `:build-gradle`:

```
HostEvent.Log lines
       ↓
parseKotlinDiagnostic / parseAapt2Diagnostic   ← per-line regex parsers
       ↓
BuildDiagnosticIngest                          ← dedupe, errors-first sort, truncate
       ↓
List<BuildDiagnostic>
       ↓
BuildDiagnosticFormatter                       ← markdown header + sections + source snippets
       ↓
diagnostics_markdown string  ← lands in AssembleDebugV2Tool's failure result JSON
```

The agent reads `diagnostics_markdown` (already a markdown-rendered
view with `### ERROR file:line:col — KOTLIN` sections + 3-line source
snippets), fixes the code, calls `assemble_debug_v2` again. No
intermediate "GetBuildDiagnosticsTool" — diagnostics ride inline with
the build tool result.

## Scope

| In | Out (deferred) |
|---|---|
| Kotlin compiler diagnostic parser (severity-first + Kotlin-2.x path-first formats) | Persistence to `build-diagnostics-{buildId}.json` (current model: inline) |
| AAPT2 diagnostic parser (with-line + no-line variants, optional `ERROR:` prefix) | Standalone `GetBuildDiagnosticsTool` (deferred — agent gets diagnostics in-line) |
| Dedupe / errors-first sort / truncate | Auto-feedback `<build-diagnostics>` block injection into next user turn (deferred — agent already sees them) |
| Markdown formatter with disk-read source snippets | KSP / dependency-resolution / Gradle cause-chain parsers |
| Wire into `AssembleDebugV2Tool` failure path | Offline regression dataset of 30 known failure cases |
| System prompt nudge: "read `diagnostics_markdown`" | |

## Executed changes

| File | Change |
|---|---|
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/diagnostic/BuildDiagnostic.kt` | New data class: severity (ERROR/WARNING), source (KOTLIN/AAPT2/GRADLE/UNKNOWN), file/line/col, message. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/diagnostic/DiagnosticParsers.kt` | Two parsers: `parseKotlinDiagnostic` (handles `e: file://...:34:15 msg`, bare paths, and Kotlin 2.x `file://...:34:15 e: msg` reorder) + `parseAapt2Diagnostic` (handles `ERROR:.../foo.xml:5:5: AAPT: error: ...` and bare-path variants). Both return `null` on non-matching lines so the ingest pipeline can blast every log line through and keep what sticks. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/diagnostic/BuildDiagnosticIngest.kt` | New `@Singleton`. Runs both parsers, dedupes by (file,line,col,message), sorts errors-first then by file then by line, truncates to `maxItems` (default 20), and optionally relativizes `file` paths against a project root. |
| `build-gradle/src/main/kotlin/com/vibe/build/gradle/diagnostic/BuildDiagnosticFormatter.kt` | New `@Singleton`. Renders the diagnostic list as markdown: header with `## Build failed: N errors[, M warnings]`, then per-diagnostic `### ERROR file:line:col — SOURCE` + message + 3-line source snippet (read from disk if `projectRoot` is provided + file resolves) with a caret pointing at the column. Gracefully drops the snippet if the file isn't readable. |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/diagnostic/DiagnosticParsersTest.kt` | 8 tests across both parsers — happy paths + "this is a continuation/snippet line, return null" negative cases. |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/diagnostic/BuildDiagnosticIngestTest.kt` | 5 tests: mixed-source ingest, dedupe, errors-first ordering, truncation, all-noise input. |
| `build-gradle/src/test/kotlin/com/vibe/build/gradle/diagnostic/BuildDiagnosticFormatterTest.kt` | 5 tests: empty list, header counts, file:line:col rendering, source-snippet read from disk, graceful degradation when file is missing. |
| `app/src/main/kotlin/com/vibe/app/feature/agent/tool/AssembleDebugV2Tool.kt` | Inject `BuildDiagnosticIngest` + `BuildDiagnosticFormatter`. On `BuildFinish(success=false)`, replace the previous "raw `errorLogs` array" output with a `diagnostics_markdown` string + structured `diagnostics` array. Keeps `failureSummary` for reference. |
| `app/src/main/assets/agent-system-prompt.md` | Updated `assemble_debug_v2` description: "On failure the result includes a `diagnostics_markdown` field — cleaned-up errors with snippets. Read it, fix, rebuild. Don't paraphrase the raw failureSummary at the user." |

## Exit criteria

- [x] `:build-gradle:testDebugUnitTest` — all 18 new diagnostic tests pass; old tests stay green.
- [x] `:app:kspDebugKotlin` + `:app:assembleDebug` — green with the new injections.
- [x] System prompt nudges the agent to use `diagnostics_markdown`.
- [ ] Manual e2e: force a Kotlin compile error (e.g. ask for "make a counter, but call `bug()` somewhere"), agent gets the diagnostic, fixes, rebuild succeeds. **(USER, please run.)**

## Exit Log (2026-04-19)

**Validated by:**
- 18 new unit tests across parsers / ingest / formatter — all green.
- `:app:kspDebugKotlin` + `:app:assembleDebug` regression — green.

**Deliberately deferred:**
- **`build-diagnostics-{buildId}.json` persistence + `GetBuildDiagnosticsTool`.** The current inline model puts diagnostics directly in the failed-build tool result, which is what the agent sees on the next turn anyway. Persisting to disk + a separate fetch tool is useful when (a) the agent fragments build/diagnostic across many turns, or (b) we want a "build history" UI panel. Neither pressure exists yet.
- **KSP / Gradle cause-chain parsers.** Most user-facing v2 build errors will be Kotlin compiler ones (typos, missing imports, type mismatches). KSP-generated-code failures and Gradle exception cause chains land in the existing `failureSummary` field; the agent can read those if Kotlin/AAPT2 produced no diagnostics. When KSP failures get common (real apps with Hilt/Room/serialization), add `parseKspDiagnostic`.
- **Auto-feedback `<build-diagnostics>` block injection into the next user turn.** The current shape (agent reads tool result → fixes → re-calls build) already trains the agent to converge on green builds. Auto-injection is for cases where the agent forgets to act on errors, which we haven't observed.
- **Offline regression dataset.** Thirty hand-curated failure cases would let us evaluate dedupe/cleanup quality systematically. For Phase 4 we trust the unit tests + manual e2e to catch egregious bugs.

**Phase 4 is complete on the code side.** End-to-end manual verification —
intentionally introduce a Kotlin error, watch the agent recover within
2-3 turns — is the remaining task. After that, Phase 5 (`:plugin-host` +
Shadow integration: in-process plugin APK execution) is the next major
piece if you want to keep going down the original roadmap.
