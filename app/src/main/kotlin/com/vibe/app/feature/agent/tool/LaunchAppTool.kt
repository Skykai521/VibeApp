package com.vibe.app.feature.agent.tool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.plugin.PluginLaunchProxyActivity
import com.vibe.app.plugin.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Singleton
class LaunchAppTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectManager: ProjectManager,
    private val pluginManager: PluginManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "launch_app",
        description = "Launch the most recently built APK in plugin mode and wait for it to be ready. " +
            "Call this after a successful run_build_pipeline to test the app. " +
            "Returns the initial View tree on success. " +
            "If VibeApp is in the background, a notification is posted for the user and the tool " +
            "returns status=queued — finish the turn in that case.",
        inputSchema = buildJsonObject {},
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val workspace = projectManager.openWorkspace(context.projectId)
        val signedApk = File(workspace.rootDir, "build/bin/signed.apk")

        if (!signedApk.exists()) {
            return call.errorResult("No built APK found. Run run_build_pipeline first.")
        }

        val packageName = "com.vibe.generated.p${context.projectId}"

        if (!isVibeAppInForeground()) {
            // Android 10+ forbids starting an Activity from the background. Post a
            // notification whose trampoline launches the preview with foreground
            // privileges when the user taps it, and let the agent finish the turn.
            postLaunchNotification(signedApk.absolutePath, packageName, context.projectId)
            return call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("queued"))
                    put(
                        "note",
                        JsonPrimitive(
                            "VibeApp is in the background; a notification was posted asking the user " +
                                "to tap and open the preview. Do NOT call inspect_ui/interact_ui now — " +
                                "finish the turn and report the build result.",
                        ),
                    )
                },
            )
        }

        pluginManager.launchPlugin(signedApk.absolutePath, packageName, context.projectId)

        // Wait for Inspector to bind (plugin process needs time to start)
        var inspector: com.vibe.app.plugin.IPluginInspector? = null
        for (attempt in 1..20) {
            delay(500)
            inspector = pluginManager.getInspector(context.projectId)
            if (inspector != null) break
        }

        if (inspector == null) {
            return call.errorResult("App launched but Inspector did not connect within 10s.")
        }

        // Return the initial View tree so the model can immediately see the UI
        return try {
            val viewTree = inspector.dumpViewTree("""{"scope":"visible","include_windows":true}""")
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("running"))
                    put("view_tree", JsonPrimitive(viewTree))
                },
            )
        } catch (e: Exception) {
            call.result(
                buildJsonObject {
                    put("status", JsonPrimitive("running"))
                    put("note", JsonPrimitive("App launched but view tree not yet available: ${e.message}"))
                },
            )
        }
    }

    private suspend fun isVibeAppInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Posts a high-importance notification whose tap launches [PluginLaunchProxyActivity]
     * — a foreground trampoline that can legally start the plugin container from the
     * main process. Best-effort: if POST_NOTIFICATIONS is denied the notify() is a no-op
     * and the tool still returns status=queued without crashing.
     */
    private fun postLaunchNotification(apkPath: String, packageName: String, projectId: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "plugin_launch"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "App preview", NotificationManager.IMPORTANCE_HIGH),
        )
        // Explicit component + package: keeps the PendingIntent from ever being
        // resolved against another app, satisfying the implicit-PendingIntent check.
        val intent = Intent(appContext, PluginLaunchProxyActivity::class.java).apply {
            `package` = appContext.packageName
            putExtra(PluginLaunchProxyActivity.EXTRA_APK_PATH, apkPath)
            putExtra(PluginLaunchProxyActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(PluginLaunchProxyActivity.EXTRA_PROJECT_ID, projectId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            appContext, projectId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            projectId.hashCode(),
            NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Build ready")
                .setContentText("Tap to preview the generated app")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }
}
