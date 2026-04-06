package com.vibe.app.plugin

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages plugin lifecycle with 5 process-isolated slots (plugin0..plugin4).
 * Uses LRU eviction: when all 5 slots are occupied, the oldest is replaced.
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class SlotInfo(
        val projectId: String,
        val launchTime: Long,
    )

    private val slots = arrayOfNulls<SlotInfo>(MAX_SLOTS)

    private val inspectorConnections = arrayOfNulls<ServiceConnection>(MAX_SLOTS)
    private val inspectors = arrayOfNulls<IPluginInspector>(MAX_SLOTS)

    private val inspectorServices: Array<Class<*>> = arrayOf(
        PluginInspectorSlot0::class.java,
        PluginInspectorSlot1::class.java,
        PluginInspectorSlot2::class.java,
        PluginInspectorSlot3::class.java,
        PluginInspectorSlot4::class.java,
    )

    private val slotActivities: Array<Class<*>> = arrayOf(
        PluginSlot0::class.java,
        PluginSlot1::class.java,
        PluginSlot2::class.java,
        PluginSlot3::class.java,
        PluginSlot4::class.java,
    )

    fun launchPlugin(apkPath: String, packageName: String, projectId: String = apkPath, projectName: String? = null) {
        val mainClassName = findMainActivity(apkPath, packageName)
        val slotIndex = allocateSlot(projectId)
        Log.d(TAG, "Launching plugin in slot $slotIndex: apk=$apkPath, main=$mainClassName")

        // Kill old plugin process in this slot (if still alive) before re-launching
        killPluginProcess(slotIndex)

        val intent = Intent(context, slotActivities[slotIndex]).apply {
            putExtra(PluginContainerActivity.EXTRA_APK_PATH, apkPath)
            putExtra(PluginContainerActivity.EXTRA_MAIN_CLASS, mainClassName)
            putExtra(PluginContainerActivity.EXTRA_PLUGIN_LABEL, projectName ?: packageName.substringAfterLast('.'))
            putExtra(PluginContainerActivity.EXTRA_SLOT_INDEX, slotIndex)
            putExtra(PluginContainerActivity.EXTRA_PROJECT_ID, projectId)
            // NEW_TASK: separate task (taskAffinity gives each slot its own)
            // CLEAR_TASK: replace any leftover task state in this slot
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        }
        context.startActivity(intent)
        bindInspector(slotIndex)
    }

    /**
     * Allocates a process slot. Strategy:
     * 1. Reuse slot already assigned to this projectId
     * 2. Use first empty slot
     * 3. Evict the oldest (LRU) slot — CLEAR_TASK flag replaces the old Activity
     */
    private fun allocateSlot(projectId: String): Int {
        // 1. Check if this project already has a slot
        for (i in slots.indices) {
            if (slots[i]?.projectId == projectId) {
                slots[i] = SlotInfo(projectId, System.currentTimeMillis())
                return i
            }
        }

        // 2. Find first empty slot
        for (i in slots.indices) {
            if (slots[i] == null) {
                slots[i] = SlotInfo(projectId, System.currentTimeMillis())
                return i
            }
        }

        // 3. Evict oldest (LRU) — CLEAR_TASK flag will replace the old Activity
        val oldestIndex = slots.indices.minBy { slots[it]!!.launchTime }
        Log.d(TAG, "All slots occupied, evicting slot $oldestIndex (project=${slots[oldestIndex]?.projectId})")
        slots[oldestIndex] = SlotInfo(projectId, System.currentTimeMillis())
        return oldestIndex
    }

    /**
     * Kills the plugin process running in the given slot, if any.
     * Plugin processes share the same UID as the host, so killProcess is permitted.
     */
    private fun killPluginProcess(slotIndex: Int) {
        unbindInspector(slotIndex)
        val processName = "${context.packageName}:plugin$slotIndex"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.forEach { info ->
            if (info.processName == processName) {
                Log.d(TAG, "Killing old plugin process: $processName (pid=${info.pid})")
                android.os.Process.killProcess(info.pid)
            }
        }
    }

    private fun findMainActivity(apkPath: String, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            info?.activities?.firstOrNull()?.name ?: "$packageName.MainActivity"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse plugin manifest, using default", e)
            "$packageName.MainActivity"
        }
    }

    /**
     * Finishes the plugin Activity for the given project and brings VibeApp back to the foreground.
     */
    fun finishPluginAndReturn(projectId: String) {
        val slotIndex = slots.indices.firstOrNull { slots[it]?.projectId == projectId }
        if (slotIndex != null) {
            ActivityHolder.get(slotIndex)?.finish()
        }
        // Bring VibeApp's main task to the foreground
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }

    /**
     * Returns the IPluginInspector for the given project, or null if no plugin is running.
     */
    fun getInspector(projectId: String): IPluginInspector? {
        val slotIndex = slots.indices.firstOrNull { slots[it]?.projectId == projectId }
            ?: return null
        return inspectors[slotIndex]
    }

    private fun bindInspector(slotIndex: Int) {
        unbindInspector(slotIndex)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                inspectors[slotIndex] = IPluginInspector.Stub.asInterface(service)
                Log.d(TAG, "Inspector bound for slot $slotIndex")
            }
            override fun onServiceDisconnected(name: ComponentName) {
                inspectors[slotIndex] = null
                Log.d(TAG, "Inspector disconnected for slot $slotIndex")
            }
        }
        inspectorConnections[slotIndex] = connection
        val intent = Intent(context, inspectorServices[slotIndex])
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindInspector(slotIndex: Int) {
        inspectorConnections[slotIndex]?.let { conn ->
            try {
                context.unbindService(conn)
            } catch (_: Exception) { /* already unbound */ }
        }
        inspectorConnections[slotIndex] = null
        inspectors[slotIndex] = null
    }

    companion object {
        private const val TAG = "PluginManager"
        const val MAX_SLOTS = 5
    }
}
