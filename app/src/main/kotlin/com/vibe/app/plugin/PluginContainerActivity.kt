package com.vibe.app.plugin

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tencent.shadow.core.runtime.HostActivityDelegator
import com.tencent.shadow.core.runtime.ShadowActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Base proxy Activity that hosts a plugin. Extends AppCompatActivity so
 * the plugin gets full Material/AppCompat support (themes, toolbar, etc.).
 *
 * Subclassed by PluginSlot0..4, each in a separate process.
 */
open class PluginContainerActivity : AppCompatActivity(), HostActivityDelegator {

    private var pluginActivity: ShadowActivity? = null
    private var pluginResources: Resources? = null
    private var pluginClassLoader: ClassLoader? = null
    private var pluginLayoutInflater: LayoutInflater? = null
    private var projectId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS)
        val pluginLabel = intent.getStringExtra(EXTRA_PLUGIN_LABEL)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)

        if (apkPath == null || mainClass == null) {
            Log.e(TAG, "Missing apkPath or mainClass in intent")
            writeErrorLog("Missing apkPath or mainClass in intent")
            finish()
            return
        }

        // Set task label for recent apps
        if (pluginLabel != null) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(pluginLabel))
        }

        try {
            pluginClassLoader = PluginResourceLoader.createPluginClassLoader(
                context = this,
                apkPath = apkPath,
                parentClassLoader = ShadowActivity::class.java.classLoader!!,
            )
            // Plugin APK already contains AndroidX/Material resources (via AAPT2 -R overlay).
            // We just need to apply the plugin's own Theme.MyApplication so ?attr/ resolves.
            pluginResources = PluginResourceLoader.loadPluginResources(this, apkPath)
            val pluginTheme = pluginResources!!.newTheme()
            val pluginPackage = mainClass!!.substringBeforeLast('.')
            val themeResId = pluginResources!!.getIdentifier(
                "Theme.MyApplication", "style", pluginPackage,
            )
            if (themeResId != 0) {
                pluginTheme.applyStyle(themeResId, true)
            }

            // Use the base context's LayoutInflater (system PhoneLayoutInflater)
            // instead of AppCompatActivity's to avoid the AppCompat Factory2
            // creating views from the host ClassLoader. Plugin views must come
            // from pluginClassLoader so their types match the plugin's own
            // AndroidX/Material classes (loaded from plugin DEX, not host).
            val baseInflater = baseContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            pluginLayoutInflater = baseInflater.cloneInContext(object : android.content.ContextWrapper(this) {
                override fun getResources(): Resources = pluginResources!!
                override fun getClassLoader(): ClassLoader = pluginClassLoader!!
                override fun getTheme(): Resources.Theme = pluginTheme
            })

            // Initialize AppLogger in the plugin's ClassLoader so logs go to the project's log directory
            initPluginLogger(mainClass)

            val clazz = pluginClassLoader!!.loadClass(mainClass)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is ShadowActivity) {
                pluginActivity = instance
                instance.setHostDelegator(this)
                Log.d(TAG, "Plugin activity loaded: $mainClass")
                try {
                    instance.performCreate(savedInstanceState)
                } catch (e: Exception) {
                    Log.e(TAG, "Plugin crashed during onCreate", e)
                    writeCrashLog(e)
                    finish()
                    return
                }
            } else {
                val error = "$mainClass is not a ShadowActivity subclass"
                Log.e(TAG, error)
                writeErrorLog(error)
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin", e)
            writeCrashLog(e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            pluginActivity?.performResume()
        } catch (e: Exception) {
            Log.e(TAG, "Plugin crashed during onResume", e)
            writeCrashLog(e)
            finish()
        }
    }

    override fun onPause() {
        try {
            pluginActivity?.performPause()
        } catch (e: Exception) {
            Log.e(TAG, "Plugin crashed during onPause", e)
            writeCrashLog(e)
        }
        super.onPause()
    }

    override fun onStop() {
        try {
            pluginActivity?.performStop()
        } catch (e: Exception) {
            Log.e(TAG, "Plugin crashed during onStop", e)
            writeCrashLog(e)
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            pluginActivity?.performDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Plugin crashed during onDestroy", e)
            writeCrashLog(e)
        }
        pluginActivity = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Don't call super.onBackPressed() — it accesses FragmentManager
        // which is not initialized (plugin is not a real system Activity).
        // Just finish the container.
        finish()
    }

    // --- HostActivityDelegator ---

    override fun getHostContext(): Context = this
    override fun getHostResources(): Resources = pluginResources ?: super.getResources()
    override fun getHostLayoutInflater(): LayoutInflater = pluginLayoutInflater ?: layoutInflater
    override fun getHostWindow(): Window = window
    override fun getHostWindowManager(): WindowManager = windowManager
    override fun getPluginClassLoader(): ClassLoader = pluginClassLoader ?: classLoader

    override fun superSetContentView(layoutResID: Int) {
        val view = (pluginLayoutInflater ?: layoutInflater).inflate(layoutResID, null)
        super.setContentView(view)
    }

    override fun superSetContentView(view: View) = super.setContentView(view)
    override fun <T : View> superFindViewById(id: Int): T = findViewById(id)
    override fun superStartActivity(intent: Intent) = super.startActivity(intent)
    override fun superFinish() = super.finish()
    override fun setPluginResult(resultCode: Int, data: Intent?) = setResult(resultCode, data)
    override fun getHostIntent(): Intent = intent

    private fun initPluginLogger(mainClass: String) {
        val pid = projectId ?: return
        val cl = pluginClassLoader ?: return
        val logDir = File(filesDir, "projects/$pid/logs")
        logDir.mkdirs()
        try {
            val packageName = mainClass.substringBeforeLast('.')
            val loggerClass = cl.loadClass("$packageName.AppLogger")
            loggerClass.getMethod("init", File::class.java).invoke(null, logDir)
            Log.d(TAG, "AppLogger initialized for project $pid")
        } catch (_: ClassNotFoundException) {
            Log.d(TAG, "Plugin has no AppLogger, skipping log init")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init AppLogger for plugin", e)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        writeErrorLog(Log.getStackTraceString(throwable))
    }

    private fun writeErrorLog(message: String) {
        val pid = projectId ?: return
        try {
            val logDir = File(filesDir, "projects/$pid/logs")
            logDir.mkdirs()
            val crashFile = File(logDir, "crash.log")
            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date())
            crashFile.appendText(
                "--- CRASH $timestamp ---\n$message\n",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write crash log", e)
        }
    }

    companion object {
        private const val TAG = "PluginContainer"
        const val EXTRA_APK_PATH = "plugin_apk_path"
        const val EXTRA_MAIN_CLASS = "plugin_main_class"
        const val EXTRA_PLUGIN_LABEL = "plugin_label"
        const val EXTRA_SLOT_INDEX = "plugin_slot_index"
        const val EXTRA_PROJECT_ID = "plugin_project_id"
    }
}

// 5 process-isolated slots — each declared in manifest with its own process
class PluginSlot0 : PluginContainerActivity()
class PluginSlot1 : PluginContainerActivity()
class PluginSlot2 : PluginContainerActivity()
class PluginSlot3 : PluginContainerActivity()
class PluginSlot4 : PluginContainerActivity()
