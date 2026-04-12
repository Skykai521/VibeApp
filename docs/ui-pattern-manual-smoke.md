# UI Pattern Library — Manual On-Device Smoke

JVM unit tests (`PatternXmlValidityTest`) verify only XML well-formedness.
Real AAPT2 resolution of `?attr/...` and `@style/TextAppearance.MaterialComponents.*`
requires running the on-device build pipeline, because `Aapt2Jni` needs an
Android `Context`.

## When to smoke

After adding or modifying any file under `app/src/main/assets/patterns/`.

## How to smoke

1. Install a debug build of VibeApp on an emulator or device.
2. Create a new project and in the agent chat say:
   > Build a test screen that uses the pattern `<pattern_id>`. Copy the
   > layout verbatim into `res/layout/test_pattern.xml` and wire it into
   > MainActivity.
3. Run the on-device build pipeline (the agent's `run_build_pipeline` tool).
4. Verify the build succeeds. If AAPT2 errors, the failing attr/style is in
   the test log — fix the pattern's `layout.xml`.

## Coverage goal

Before shipping Phase D, at least one block and one screen template per
category should be smoke-tested on device.
