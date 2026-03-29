# Theme Stability & Build Performance Optimization

## Problem Analysis

Based on the diagnostic log from a real generation session (kimi-k2.5, "帮我实现一个 material design 的示例程序"):

### Problem 1: Theme Compilation Failures

**Symptom:** The model generated 3 build failures before succeeding, all theme-related:
- Build 1 (iteration 3): AAPT2 link failed — 17 errors (likely Material3 theme references)
- Build 2 (iteration 4): AAPT2 link failed — 3 errors (theme still wrong after first fix)
- Build 3 (iteration 5): Javac failed — 1 error (MaterialSwitch, setSupportActionBar issue)
- Build 4 (iteration 6): SUCCESS

**Root cause analysis from chat.md:**
1. Model first tried `Material3` theme (not available — only `MaterialComponents` is bundled)
2. After fix, still had theme-related AAPT2 errors
3. Used `MaterialSwitch` (doesn't exist in bundled version)
4. Used `setSupportActionBar` incorrectly

**Why this keeps happening:**
- The system prompt says "ALWAYS use `Theme.MaterialComponents.DayNight.NoActionBar`" but models often ignore this or use Material3/MaterialDesign3 variants
- The template `themes.xml` already has the correct theme, but the model **overwrites it** with a wrong one
- Models frequently confuse Material3 (`Theme.Material3.*`) with MaterialComponents (`Theme.MaterialComponents.*`)
- The model doesn't know which Material widget classes actually exist in the bundled JAR

### Problem 2: Slow Generation (242 seconds total)

**Time breakdown from diagnostic log:**

| Phase | Duration | Notes |
|-------|----------|-------|
| Model call 1 (initial generation) | 90.7s | First byte latency 90.7s (!), 8 tool calls |
| Model call 2 (clean_build_cache) | 2.2s | |
| Model call 3 (run_build) | 0.9s | |
| Build 1 (AAPT2 fail) | 0.6s | |
| Model call 4 (fix attempt 1) | 10.8s | |
| Build 2 (AAPT2 fail) | 0.5s | |
| Model call 5 (fix attempt 2) | 34.0s | |
| Build 3 (Javac fail) | 6.9s | |
| Model call 6 (fix attempt 3) | 54.1s | |
| Build 4 (SUCCESS) | 34.9s | DEX stage: 28.4s |
| Model call 7 (final response) | 6.4s | |
| **Total** | **242.7s** | |

**Bottleneck analysis:**
1. **Model latency** dominates: 199s / 242s = **82% of total time** is waiting for the model
2. **Fix loop overhead**: 3 failed builds + 3 fix model calls = ~106s wasted on fixable errors
3. **DEX stage**: 28.4s for the successful build (65% of build time) — D8 processing the large `androidx-classes.jar`
4. **First model call**: 90.7s — very large due to non-streaming mode and initial code generation

---

## Proposed Solutions

### Solution A: Eliminate Theme Errors via Template Lock (High Impact, Low Effort)

**Core idea:** The template already has a correct `themes.xml` and `colors.xml`. Tell the model to **never touch** these files.

**Changes:**

1. **System prompt update** — Add to NEVER section:
   ```
   - NEVER modify or overwrite themes.xml or colors.xml — the template theme is pre-configured and correct
   - NEVER use Theme.Material3.*, Theme.MaterialComponents.Light.*, or any theme other than the pre-configured Theme.MyApplication
   - NEVER use MaterialSwitch, SwitchMaterial — use android.widget.Switch instead
   ```

2. **System prompt update** — Modify ALWAYS section:
   ```
   - ALWAYS use android:theme="@style/Theme.MyApplication" in AndroidManifest.xml — this theme is already configured with Material Components support
   - ALWAYS reference the existing theme by name (Theme.MyApplication) — do NOT redefine it
   ```

3. **Template themes.xml enhancement** — Add commonly needed theme overlays as pre-defined styles:
   ```xml
   <!-- Already configured, ready to use -->
   <style name="Theme.MyApplication" parent="Theme.MaterialComponents.DayNight.NoActionBar">
       <item name="colorPrimary">@color/purple_500</item>
       <item name="colorPrimaryVariant">@color/purple_700</item>
       <item name="colorOnPrimary">@android:color/white</item>
       <item name="colorSecondary">@color/teal_200</item>
       <item name="colorSecondaryVariant">@color/teal_700</item>
       <item name="colorOnSecondary">@android:color/black</item>
       <item name="android:statusBarColor">@color/purple_700</item>
       <!-- Surface colors for cards and backgrounds -->
       <item name="colorSurface">@android:color/white</item>
       <item name="colorOnSurface">@android:color/black</item>
   </style>

   <!-- Use this variant if the user wants a Toolbar -->
   <style name="Theme.MyApplication.Toolbar" parent="Theme.MaterialComponents.DayNight.NoActionBar">
       <item name="colorPrimary">@color/purple_500</item>
       <item name="colorPrimaryVariant">@color/purple_700</item>
       <item name="colorOnPrimary">@android:color/white</item>
       <item name="colorSecondary">@color/teal_200</item>
       <item name="colorSecondaryVariant">@color/teal_700</item>
       <item name="colorOnSecondary">@android:color/black</item>
       <item name="android:statusBarColor">@color/purple_700</item>
   </style>
   ```

4. **System prompt — add default template file list:**
   ```
   ## Pre-configured Template Files (DO NOT overwrite)
   - src/main/res/values/themes.xml — Material Components theme (Theme.MyApplication)
   - src/main/res/values/colors.xml — Default color palette
   - src/main/AndroidManifest.xml — Pre-configured with Theme.MyApplication
   These files are already correct. Only modify them if the user specifically asks to change colors or theme.
   ```

**Expected impact:** Eliminates ~90% of theme-related build failures. The model only needs to write Java + layout XML, never touching theme config.

### Solution B: DEX Stage Optimization (Medium Impact, Medium Effort)

**Problem:** D8 converting `androidx-classes.jar` (~5MB) takes 15-28 seconds every build.

**Solutions:**

1. **Pre-DEX androidx-classes.jar** — Convert AndroidX to DEX once and cache:
   ```
   First build:  androidx-classes.jar → D8 → cached-androidx.dex (~16s)
   Later builds: skip AndroidX DEX, only DEX user code (~2s)
                 merge cached-androidx.dex + user classes.dex at PACKAGE stage
   ```
   Estimated savings: ~15-25s per build after the first.

2. **Pre-DEX shadow-runtime.jar** — Same approach, smaller gain (~1s).

3. **Skip clean_build_cache** — The system prompt tells the model to call `clean_build_cache` before every build. If AAPT2/Javac can handle incremental builds, this wastes time.

### Solution C: Reduce Fix Loop Iterations (High Impact, Medium Effort)

**Problem:** Each failed build triggers a model call (10-54s) + rebuild. 3 failures = ~106s wasted.

**Solutions:**

1. **Better system prompt examples** — Add concrete code snippets the model can copy:
   ```
   ## Quick Reference: Common Patterns

   ### Adding a Toolbar
   XML: <com.google.android.material.appbar.MaterialToolbar ... />
   Java: setSupportActionBar(findViewById(R.id.toolbar));
   Theme: already configured (Theme.MyApplication)

   ### Switch/Toggle
   Use: android.widget.Switch (NOT MaterialSwitch/SwitchMaterial)
   ```

2. **Validate before build** — Add a pre-build check in the build pipeline:
   - Scan `themes.xml` for known-bad theme parents (Material3, DarkActionBar)
   - Auto-fix to `Theme.MaterialComponents.DayNight.NoActionBar` before AAPT2 runs
   - This catches the #1 error source without a model round-trip

3. **Smarter error messages** — When AAPT2 fails with "resource not found" for a theme, append a hint:
   ```
   HINT: Use Theme.MaterialComponents.DayNight.NoActionBar as parent theme.
   Do NOT use Theme.Material3.* or Theme.MaterialComponents.Light.DarkActionBar.
   ```

### Solution D: Model Latency Optimization (Medium Impact, Low Effort)

**Problem:** First model call takes 90.7s with streaming disabled.

**Solutions:**

1. **Enable streaming** — If currently using `stream: false`, switch to `stream: true`. This enables progressive tool call processing and reduces perceived latency.

2. **Reduce system prompt token count** — Current prompt is 5243 chars. Removing redundant information (like the bundled library list which can be inferred from the template) could save ~1000 tokens per request.

3. **Reduce context growth** — Each fix iteration adds tool call results to the conversation. The 6th model call had 79KB of context. Trimming build error logs to only the first 5 errors instead of all would reduce context size.

---

## Recommended Implementation Order

| Priority | Solution | Expected Gain | Effort |
|----------|----------|---------------|--------|
| **P0** | A: Template lock + system prompt | Eliminates 3/4 build failures in this session | 1 hour |
| **P1** | C2: Pre-build theme validation | Catches remaining theme errors without model round-trip | 2 hours |
| **P2** | B1: Pre-DEX AndroidX caching | -15~25s per build | 1 day |
| **P3** | D1: Enable streaming | Better perceived latency | Config change |
| **P4** | C1: System prompt examples | Fewer fix iterations | 1 hour |

**P0 alone** would reduce this session from 242s to ~110s (eliminate 3 fix cycles: 106s model time + 8s build time).

**P0 + P2** would bring successful builds to ~10-12s (from 35s), making total generation time ~60-70s for a typical first attempt.
