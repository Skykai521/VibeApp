# Vendored Tencent Shadow

This directory holds a vendored copy of the [Tencent Shadow](https://github.com/Tencent/Shadow)
plugin framework's SDK source code. We use Shadow to host plugin APKs
inside VibeApp with full Android-runtime support (Fragment + Snackbar +
Service / BroadcastReceiver / ContentProvider + intra-plugin Activity
navigation), replacing v1's hand-rolled DexClassLoader-based plugin
layer (which only handled bare Activities and missed all of the above).

See `docs/superpowers/plans/2026-04-19-v2-phase-5b-shadow-full-integration.md`
for the full integration plan.

## Layout

- `upstream/` — raw, unmodified copy of Shadow's `projects/sdk/core/` and
  `projects/sdk/dynamic/` source trees (Java + Kotlin only — Shadow's
  own Gradle wrappers / build files are pruned out, see "Pruned" below).
  This serves as the **reference** copy we cherry-pick from for the next
  phases. Do NOT add it to `settings.gradle.kts` — it doesn't compile
  standalone.
- (future) `runtime/`, `loader/`, `manager/`, `gradle-plugin/` — our own
  modules wrapping the Shadow source files we actually use, with
  `build.gradle.kts` files written from scratch to fit our toolchain
  (Kotlin 2.3.10, AGP 9.1.0). Phase 5b-2 onwards will land these.

## Upstream pin

- Repository: `https://github.com/Tencent/Shadow`
- Branch: `master`
- Commit: `4e9f70a660cc0e55e0576650258e732382ca9031` (2026-03-24)
- Reason for HEAD-not-tag: latest tag `2.1.2` is from May 2022 and
  pre-dates AGP 8 compat work. HEAD is actively maintained as of
  March 2026, with explicit AGP 8.9.0 + ContentProvider improvements
  not in any released tag.

## Pruned (vs upstream)

The following were stripped because we own the build:

- `core/build.gradle` (root build script — replaced by our composed setup)
- `core/settings.gradle` (root settings — replaced by our `settings.gradle.kts`)
- `core/gradle.properties`
- `core/gradle/`, `core/gradlew*` (their wrapper, we use ours)
- `dynamic/build.gradle`, `dynamic/gradle/`
- `core/manager-db-test/` (test-only)

## Forking discipline

Edit freely. Do **not** treat `upstream/` as a passive mirror — when our
modules need a Shadow class behavior changed (e.g. `ShadowActivity`
extends `ComponentActivity` for Compose lifecycle support — see Phase
5b-6), we'll fork the relevant file into our own module and modify it
there, leaving `upstream/` as the historical reference.

## License

Tencent Shadow is BSD 3-Clause. Notice retained in `upstream/LICENSE.txt`
(if present in the vendor — otherwise see the original LICENSE.txt at
the upstream repo).
