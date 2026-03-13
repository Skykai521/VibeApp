package com.vibe.build.engine.compiler

import android.content.Context

@Deprecated("build-engine now compiles with JavacTool. Use JavacCompiler instead.")
class EcjCompiler(
    context: Context,
) : JavacCompiler(context)
