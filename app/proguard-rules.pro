# ===========================================================================
# VibeApp ProGuard/R8 Rules
# ===========================================================================

# ---------------------------------------------------------------------------
# General
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# Kotlin Serialization
# Keep all @Serializable classes and their generated serializers.
# The kotlinx.serialization compiler plugin generates $$serializer companion
# objects that are accessed reflectively.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations
-keep,includedescriptorclasses class com.vibe.app.data.dto.** { *; }
-keepclassmembers class com.vibe.app.data.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.vibe.app.data.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep kotlinx-serialization infrastructure
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Keep @Serializable enums from being obfuscated (SerialName values must match)
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *;
}

# ---------------------------------------------------------------------------
# Build Engine – javac / javax.tools
# The on-device Java compiler uses com.sun.tools.javac.* and javax.tools.*
# via direct API calls and internal service loading. These must not be
# removed or renamed.
#
# javac-new.jar bundles java.lang.reflect.* and other JDK stubs that
# overlap with the Android SDK library classes. Suppress the R8
# "Library class implements program class" errors for these.
# ---------------------------------------------------------------------------
-dontwarn java.lang.reflect.AnnotatedElement
-dontwarn java.lang.reflect.Type
-dontwarn java.lang.reflect.GenericDeclaration
-dontwarn org.xmlpull.v1.XmlPullParser
-dontwarn org.xmlpull.v1.**

-keep class com.sun.tools.javac.** { *; }
-keep class javax.tools.** { *; }
-keep class jdk.** { *; }
-keep class org.openjdk.** { *; }

# Build-engine public API
-keep class com.vibe.build.engine.** { *; }

# Build-logic / common modules used by build-engine
-keep class com.vibe.build.logic.** { *; }
-keep class com.vibe.build.common.** { *; }

# ---------------------------------------------------------------------------
# JAXP / XML Parsers (used by build-engine for resource processing)
# ---------------------------------------------------------------------------
-keep class org.openjdk.javax.xml.** { *; }
-keep class org.openjdk.com.sun.org.apache.** { *; }
-dontwarn org.openjdk.**

# ---------------------------------------------------------------------------
# Plugin System – Shadow Runtime (reflection-heavy)
# PluginContainerActivity loads plugin classes via ClassLoader.loadClass()
# and instantiates them with getDeclaredConstructor().newInstance().
# ---------------------------------------------------------------------------
-keep class com.tencent.shadow.** { *; }
-keep class com.vibe.app.plugin.** { *; }

# Plugin slot classes are referenced by class literal in PluginManager
-keep class com.vibe.app.plugin.PluginSlot0
-keep class com.vibe.app.plugin.PluginSlot1
-keep class com.vibe.app.plugin.PluginSlot2
-keep class com.vibe.app.plugin.PluginSlot3
-keep class com.vibe.app.plugin.PluginSlot4
-keep class com.vibe.app.plugin.PluginInspectorSlot0
-keep class com.vibe.app.plugin.PluginInspectorSlot1
-keep class com.vibe.app.plugin.PluginInspectorSlot2
-keep class com.vibe.app.plugin.PluginInspectorSlot3
-keep class com.vibe.app.plugin.PluginInspectorSlot4

# PluginContainerActivity reflectively creates CoordinatorLayout
-keep class androidx.coordinatorlayout.widget.CoordinatorLayout {
    <init>(android.content.Context);
}

# ---------------------------------------------------------------------------
# Room Database
# ---------------------------------------------------------------------------
-keep class com.vibe.app.data.database.** { *; }
-keep class com.vibe.app.data.database.entity.** { *; }
-keep class com.vibe.app.data.database.dao.** { *; }

# TypeConverters accessed reflectively by Room
-keep class com.vibe.app.data.database.entity.StringListConverter { *; }
-keep class com.vibe.app.data.database.entity.ProjectBuildStatusConverter { *; }

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class com.vibe.app.di.** { *; }
-dontwarn dagger.hilt.internal.**

# ---------------------------------------------------------------------------
# Ktor (OkHttp engine + Content Negotiation)
# ---------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# ---------------------------------------------------------------------------
# Coroutines
# ---------------------------------------------------------------------------
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# Reactive / Netty dependencies (transitive, may not all be present)
# ---------------------------------------------------------------------------
-keep class reactor.** { *; }
-keep class io.micrometer.** { *; }
-dontwarn io.micrometer.**
-dontwarn reactor.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# ---------------------------------------------------------------------------
# Data models (used by DataStore / serialization)
# ---------------------------------------------------------------------------
-keep class com.vibe.app.data.model.** { *; }

# ---------------------------------------------------------------------------
# Coil (image loading)
# ---------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# ---------------------------------------------------------------------------
# Android SDK / apksig (used by build-engine for APK signing)
# ---------------------------------------------------------------------------
-keep class com.android.tools.build.apksig.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.android.tools.build.apksig.**

# ---------------------------------------------------------------------------
# Compose / Material3 – generally safe, but keep stability annotations
# ---------------------------------------------------------------------------
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------------
# ServiceLoader patterns in build-tools
# ---------------------------------------------------------------------------
-keepnames class * implements java.util.ServiceLoader
-keep class META-INF.services.** { *; }

# ---------------------------------------------------------------------------
# Chucker (debug HTTP inspector – release is no-op)
# ---------------------------------------------------------------------------
-dontwarn com.chuckerteam.chucker.**

# ---------------------------------------------------------------------------
# Misc dontwarn for optional / compile-only dependencies
# ---------------------------------------------------------------------------
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**

# Build-tools missing classes (JDK internals / optional dependencies)
-dontwarn com.google.auto.service.AutoService
-dontwarn jdk.internal.jrtfs.SystemImage
-dontwarn sun.security.pkcs.**
-dontwarn sun.security.util.**
-dontwarn sun.security.x509.**
