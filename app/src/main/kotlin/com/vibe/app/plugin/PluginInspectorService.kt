package com.vibe.app.plugin

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Static registry for passing Activity references between
 * PluginContainerActivity and PluginInspectorService in the same process.
 */
object ActivityHolder {
    private val activities = arrayOfNulls<Activity>(PluginManager.MAX_SLOTS)

    fun set(slotIndex: Int, activity: Activity) {
        activities[slotIndex] = activity
    }

    fun get(slotIndex: Int): Activity? = activities.getOrNull(slotIndex)

    fun clear(slotIndex: Int) {
        if (slotIndex in activities.indices) {
            activities[slotIndex] = null
        }
    }
}

/**
 * Bound Service running in each plugin process.
 * Traverses the View tree and executes UI actions on behalf of the AI Agent.
 */
open class PluginInspectorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Subclasses override to return their slot index. */
    protected open val slotIndex: Int = -1

    private val binder = object : IPluginInspector.Stub() {

        override fun dumpViewTree(): String {
            return runOnMainThreadBlocking {
                val activity = ActivityHolder.get(slotIndex)
                    ?: return@runOnMainThreadBlocking """{"error":"no activity"}"""
                val root = activity.window?.decorView
                    ?: return@runOnMainThreadBlocking """{"error":"no decor view"}"""
                dumpView(root, 0).toString()
            }
        }

        override fun performClick(selector: String): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                    ?: return@runOnMainThreadBlocking """{"success":false,"error":"view not found"}"""
                view.performClick()
                """{"success":true}"""
            }
        }

        override fun inputText(selector: String, text: String): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                if (view !is EditText) {
                    return@runOnMainThreadBlocking """{"success":false,"error":"EditText not found"}"""
                }
                view.setText(text)
                """{"success":true}"""
            }
        }

        override fun scroll(selector: String, direction: String, amount: Int): String {
            return runOnMainThreadBlocking {
                val view = findViewBySelector(selector)
                    ?: return@runOnMainThreadBlocking """{"success":false,"error":"view not found"}"""
                when (direction) {
                    "up" -> view.scrollBy(0, -amount)
                    "down" -> view.scrollBy(0, amount)
                    "left" -> view.scrollBy(-amount, 0)
                    "right" -> view.scrollBy(amount, 0)
                    else -> return@runOnMainThreadBlocking """{"success":false,"error":"invalid direction: $direction"}"""
                }
                """{"success":true}"""
            }
        }

        override fun performGesture(gestureJson: String): String {
            return """{"success":false,"error":"gesture not yet supported"}"""
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    // --- View tree traversal ---

    private fun dumpView(view: View, depth: Int): JSONObject {
        val node = JSONObject()
        node.put("class", view.javaClass.simpleName)

        if (view.id != View.NO_ID) {
            try {
                node.put("id", view.resources.getResourceEntryName(view.id))
            } catch (_: Exception) { /* dynamic or unnamed ID */ }
        }

        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            node.put("text", if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "\u2026" else text)
            if (view is EditText) {
                node.put("hint", view.hint?.toString() ?: "")
            }
        }

        view.contentDescription?.let { desc ->
            val s = desc.toString()
            node.put("contentDescription", if (s.length > MAX_TEXT_LENGTH) s.take(MAX_TEXT_LENGTH) + "\u2026" else s)
        }

        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        node.put("bounds", JSONObject().apply {
            put("left", loc[0])
            put("top", loc[1])
            put("right", loc[0] + view.width)
            put("bottom", loc[1] + view.height)
        })

        node.put("visibility", when (view.visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            else -> "gone"
        })
        node.put("clickable", view.isClickable)
        node.put("enabled", view.isEnabled)
        node.put("focusable", view.isFocusable)

        if (view is ViewGroup && depth < MAX_DEPTH) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.GONE) continue
                children.put(dumpView(child, depth + 1))
            }
            node.put("children", children)
        }

        return node
    }

    // --- View finding ---

    private fun findViewBySelector(selectorJson: String): View? {
        val activity = ActivityHolder.get(slotIndex) ?: return null
        val root = activity.window?.decorView ?: return null
        val sel = JSONObject(selectorJson)
        val type = sel.getString("type")
        val value = sel.getString("value")
        val index = sel.optInt("index", 0)
        return findViewRecursive(root, type, value, index, intArrayOf(0))
    }

    private fun findViewRecursive(
        view: View,
        type: String,
        value: String,
        targetIndex: Int,
        counter: IntArray,
    ): View? {
        if (matchesSelector(view, type, value)) {
            if (counter[0] == targetIndex) return view
            counter[0]++
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewRecursive(view.getChildAt(i), type, value, targetIndex, counter)
                if (found != null) return found
            }
        }
        return null
    }

    private fun matchesSelector(view: View, type: String, value: String): Boolean {
        return when (type) {
            "id" -> {
                if (view.id == View.NO_ID) return false
                try {
                    view.resources.getResourceEntryName(view.id) == value
                } catch (_: Exception) { false }
            }
            "text" -> (view as? TextView)?.text?.toString() == value
            "text_contains" -> (view as? TextView)?.text?.toString()?.contains(value) == true
            "desc" -> view.contentDescription?.toString() == value
            "class" -> view.javaClass.simpleName == value
            else -> false
        }
    }

    // --- Thread helpers ---

    private fun <T> runOnMainThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TimeoutException("Main thread operation timed out after ${TIMEOUT_SECONDS}s")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        private const val MAX_DEPTH = 15
        private const val MAX_TEXT_LENGTH = 100
        private const val TIMEOUT_SECONDS = 5L
    }
}

// 5 process-isolated inspector slots — each declared in manifest with matching plugin process
class PluginInspectorSlot0 : PluginInspectorService() { override val slotIndex = 0 }
class PluginInspectorSlot1 : PluginInspectorService() { override val slotIndex = 1 }
class PluginInspectorSlot2 : PluginInspectorService() { override val slotIndex = 2 }
class PluginInspectorSlot3 : PluginInspectorService() { override val slotIndex = 3 }
class PluginInspectorSlot4 : PluginInspectorService() { override val slotIndex = 4 }
