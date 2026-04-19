package com.vibe.app.plugin.v2

import android.util.Log
import com.tencent.shadow.core.common.ILoggerFactory
import com.tencent.shadow.core.common.Logger
import com.tencent.shadow.core.common.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges Shadow's SLF4J-shaped `Logger` API to `android.util.Log`.
 *
 * Shadow classes that hold a static logger (e.g. `BasePluginManager`,
 * `BaseDynamicPluginManager`, `PluginProcessService`) resolve it
 * eagerly in their `<clinit>` via
 * `LoggerFactory.getLogger(Class)`. If no `ILoggerFactory` has been
 * registered by then, that call throws and kills the host process
 * before Hilt can even finish building the graph.
 *
 * [install] must therefore run from `VibeApp.Application.onCreate`,
 * before any code path (Hilt, Compose, tools) can touch a Shadow
 * class. Idempotent — safe to call from every process the host
 * spawns. Shadow's `setILoggerFactory` itself rejects a second call
 * by design, so we guard with an AtomicBoolean instead of relying on
 * that exception.
 */
object ShadowLogBridge {

    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        LoggerFactory.setILoggerFactory(AndroidLoggerFactory)
    }

    private object AndroidLoggerFactory : ILoggerFactory {
        override fun getLogger(name: String): Logger = AndroidLogger(truncateTag(name))
    }

    /**
     * Android tags are capped at 23 characters on API < 26. Shadow
     * logger names are fully-qualified class names ("com.tencent...")
     * — chop from the left so what we keep is the class name, which
     * is what's most useful in logcat.
     */
    private fun truncateTag(name: String): String {
        if (name.length <= 23) return name
        return name.takeLast(23)
    }

    private class AndroidLogger(private val tag: String) : Logger {
        override fun getName(): String = tag

        override fun isTraceEnabled(): Boolean = Log.isLoggable(tag, Log.VERBOSE)
        override fun trace(msg: String) { Log.v(tag, msg) }
        override fun trace(format: String, arg: Any?) { Log.v(tag, format(format, arg)) }
        override fun trace(format: String, arg1: Any?, arg2: Any?) { Log.v(tag, format(format, arg1, arg2)) }
        override fun trace(format: String, vararg arguments: Any?) { Log.v(tag, format(format, *arguments)) }
        override fun trace(msg: String, t: Throwable) { Log.v(tag, msg, t) }

        override fun isDebugEnabled(): Boolean = Log.isLoggable(tag, Log.DEBUG)
        override fun debug(msg: String) { Log.d(tag, msg) }
        override fun debug(format: String, arg: Any?) { Log.d(tag, format(format, arg)) }
        override fun debug(format: String, arg1: Any?, arg2: Any?) { Log.d(tag, format(format, arg1, arg2)) }
        override fun debug(format: String, vararg arguments: Any?) { Log.d(tag, format(format, *arguments)) }
        override fun debug(msg: String, t: Throwable) { Log.d(tag, msg, t) }

        override fun isInfoEnabled(): Boolean = true
        override fun info(msg: String) { Log.i(tag, msg) }
        override fun info(format: String, arg: Any?) { Log.i(tag, format(format, arg)) }
        override fun info(format: String, arg1: Any?, arg2: Any?) { Log.i(tag, format(format, arg1, arg2)) }
        override fun info(format: String, vararg arguments: Any?) { Log.i(tag, format(format, *arguments)) }
        override fun info(msg: String, t: Throwable) { Log.i(tag, msg, t) }

        override fun isWarnEnabled(): Boolean = true
        override fun warn(msg: String) { Log.w(tag, msg) }
        override fun warn(format: String, arg: Any?) { Log.w(tag, format(format, arg)) }
        override fun warn(format: String, vararg arguments: Any?) { Log.w(tag, format(format, *arguments)) }
        override fun warn(format: String, arg1: Any?, arg2: Any?) { Log.w(tag, format(format, arg1, arg2)) }
        override fun warn(msg: String, t: Throwable) { Log.w(tag, msg, t) }

        override fun isErrorEnabled(): Boolean = true
        override fun error(msg: String) { Log.e(tag, msg) }
        override fun error(format: String, arg: Any?) { Log.e(tag, format(format, arg)) }
        override fun error(format: String, arg1: Any?, arg2: Any?) { Log.e(tag, format(format, arg1, arg2)) }
        override fun error(format: String, vararg arguments: Any?) { Log.e(tag, format(format, *arguments)) }
        override fun error(msg: String, t: Throwable) { Log.e(tag, msg, t) }

        /**
         * SLF4J-style `{}` placeholder substitution. Shadow's internal
         * log calls use this form; implementing it cheaply avoids
         * printing unrendered format strings in logcat.
         */
        private fun format(pattern: String, vararg args: Any?): String {
            if (args.isEmpty()) return pattern
            val sb = StringBuilder(pattern.length + 32)
            var i = 0
            var argIdx = 0
            while (i < pattern.length) {
                val ph = pattern.indexOf("{}", startIndex = i)
                if (ph == -1 || argIdx >= args.size) {
                    sb.append(pattern, i, pattern.length)
                    break
                }
                sb.append(pattern, i, ph)
                sb.append(args[argIdx]?.toString() ?: "null")
                argIdx++
                i = ph + 2
            }
            return sb.toString()
        }
    }
}
