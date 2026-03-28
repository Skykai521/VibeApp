package com.vibe.build.engine.shadow

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

class ShadowClassRemapper(cv: ClassVisitor) : ClassRemapper(cv, ShadowRemapper)

object ShadowRemapper : Remapper() {

    private val mapping = mapOf(
        "android/app/Activity"
            to "com/tencent/shadow/core/runtime/ShadowActivity",
        "android/app/Application"
            to "com/tencent/shadow/core/runtime/ShadowApplication",
        "android/app/Service"
            to "com/tencent/shadow/core/runtime/ShadowService",
        "android/app/IntentService"
            to "com/tencent/shadow/core/runtime/ShadowIntentService",
        "android/app/Application\$ActivityLifecycleCallbacks"
            to "com/tencent/shadow/core/runtime/ShadowActivityLifecycleCallbacks",
    )

    override fun map(internalName: String): String = mapping[internalName] ?: internalName
}
