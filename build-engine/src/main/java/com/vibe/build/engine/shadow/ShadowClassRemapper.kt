package com.vibe.build.engine.shadow

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

class ShadowClassRemapper(cv: ClassVisitor) : ClassRemapper(cv, ShadowRemapper)

object ShadowRemapper : Remapper() {

    // Only remap Activity → ShadowActivity.
    //
    // Application, Service, IntentService, and ActivityLifecycleCallbacks
    // must NOT be remapped globally because ASM's Remapper rewrites ALL
    // references (including method descriptors). For example, remapping
    // Application causes getApplication()'s return type to change from
    // android.app.Application to ShadowApplication, but the framework's
    // Activity.getApplication() still returns Application → NoSuchMethodError.
    //
    // These classes are handled at the source/template level instead:
    // the AI agent generates `extends ShadowApplication` directly for the
    // plugin's Application class.
    private val mapping = mapOf(
        "android/app/Activity"
            to "com/tencent/shadow/core/runtime/ShadowActivity",
    )

    override fun map(internalName: String): String = mapping[internalName] ?: internalName
}
