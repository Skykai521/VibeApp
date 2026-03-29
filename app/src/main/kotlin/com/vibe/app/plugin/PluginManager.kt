package com.vibe.app.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private val slotActivities: Array<Class<*>> = arrayOf(
        PluginSlot0::class.java,
        PluginSlot1::class.java,
        PluginSlot2::class.java,
        PluginSlot3::class.java,
        PluginSlot4::class.java,
    )

    fun launchPlugin(apkPath: String, packageName: String, projectId: String = apkPath) {
        val mainClassName = findMainActivity(apkPath, packageName)
        val slotIndex = allocateSlot(projectId)
        Log.d(TAG, "Launching plugin in slot $slotIndex: apk=$apkPath, main=$mainClassName")

        val intent = Intent(context, slotActivities[slotIndex]).apply {
            putExtra(PluginContainerActivity.EXTRA_APK_PATH, apkPath)
            putExtra(PluginContainerActivity.EXTRA_MAIN_CLASS, mainClassName)
            putExtra(PluginContainerActivity.EXTRA_PLUGIN_LABEL, packageName.substringAfterLast('.'))
            putExtra(PluginContainerActivity.EXTRA_SLOT_INDEX, slotIndex)
            putExtra(PluginContainerActivity.EXTRA_PROJECT_ID, projectId)
            // NEW_TASK: separate task stack from VibeApp
            // NEW_DOCUMENT: show as separate entry in recent apps
            // CLEAR_TASK: if this slot already has a running plugin, replace it
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        }
        context.startActivity(intent)
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

    companion object {
        private const val TAG = "PluginManager"
        const val MAX_SLOTS = 5
    }
}
