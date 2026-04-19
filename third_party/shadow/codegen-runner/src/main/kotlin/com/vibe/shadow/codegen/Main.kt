package com.vibe.shadow.codegen

import com.tencent.shadow.coding.code_generator.ActivityCodeGenerator
import java.io.File

/**
 * One-shot driver for Shadow's [ActivityCodeGenerator]. Produces the
 * `Generated*` Java source files that runtime / activity-container /
 * loader normally compile. Output is committed back into the consumer
 * modules' `src/main/java/` so the runtime build doesn't re-run the
 * generator on every invocation.
 *
 * Re-run when:
 *   - The bundled android.jar changes API level meaningfully
 *   - The vendored ActivityCodeGenerator changes upstream
 *   - We discover a generated file is missing methods we need
 *
 * Run with:
 *   ./gradlew :shadow-codegen-runner:run --args="<output-root>"
 *
 * Output layout under `<output-root>` mirrors what each consumer
 * module needs to copy into its `src/main/java/`:
 *   activity_container/com/tencent/shadow/core/runtime/container/Generated*.java
 *   runtime/com/tencent/shadow/core/runtime/Generated*.java
 *   loader/com/tencent/shadow/core/loader/delegates/Generated*.java
 */
fun main(args: Array<String>) {
    val outputRoot = File(
        args.getOrNull(0) ?: error("usage: codegen-runner <output-root>"),
    ).also { it.mkdirs() }

    println("Output root: ${outputRoot.absolutePath}")

    val generator = ActivityCodeGenerator()
    listOf("activity_container", "runtime", "loader").forEach { image ->
        val dir = File(outputRoot, image).also { it.mkdirs() }
        println("Generating $image → $dir")
        generator.generate(dir, image)
    }

    println("Generated files:")
    outputRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".java") }
        .sortedBy { it.absolutePath }
        .forEach { println("  ${it.relativeTo(outputRoot)}") }
}
