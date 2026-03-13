package com.vibe.app.di

import android.content.Context
import com.vibe.build.engine.apk.AndroidApkBuilder
import com.vibe.build.engine.compiler.JavacCompiler
import com.vibe.build.engine.dex.D8DexConverter
import com.vibe.build.engine.pipeline.ApkBuilder
import com.vibe.build.engine.pipeline.ApkSigner
import com.vibe.build.engine.pipeline.BuildPipeline
import com.vibe.build.engine.pipeline.Compiler
import com.vibe.build.engine.pipeline.DefaultBuildPipeline
import com.vibe.build.engine.pipeline.DexConverter
import com.vibe.build.engine.pipeline.ResourceCompiler
import com.vibe.build.engine.resource.Aapt2ResourceCompiler
import com.vibe.build.engine.sign.DebugApkSigner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BuildEngineModule {

    @Provides
    @Singleton
    fun provideResourceCompiler(
        @ApplicationContext context: Context,
    ): ResourceCompiler = Aapt2ResourceCompiler(context)

    @Provides
    @Singleton
    fun provideCompiler(
        @ApplicationContext context: Context,
    ): Compiler = JavacCompiler(context)

    @Provides
    @Singleton
    fun provideDexConverter(
        @ApplicationContext context: Context,
    ): DexConverter = D8DexConverter(context)

    @Provides
    @Singleton
    fun provideApkBuilder(
        @ApplicationContext context: Context,
    ): ApkBuilder = AndroidApkBuilder(context)

    @Provides
    @Singleton
    fun provideApkSigner(
        @ApplicationContext context: Context,
    ): ApkSigner = DebugApkSigner(context)

    @Provides
    @Singleton
    fun provideBuildPipeline(
        @ApplicationContext context: Context,
        resourceCompiler: ResourceCompiler,
        compiler: Compiler,
        dexConverter: DexConverter,
        apkBuilder: ApkBuilder,
        apkSigner: ApkSigner,
    ): BuildPipeline = DefaultBuildPipeline(
        context = context,
        resourceCompiler = resourceCompiler,
        compiler = compiler,
        dexConverter = dexConverter,
        apkBuilder = apkBuilder,
        apkSigner = apkSigner,
    )
}
