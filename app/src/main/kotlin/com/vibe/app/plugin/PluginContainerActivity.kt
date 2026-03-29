package com.vibe.app.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.tencent.shadow.core.runtime.HostActivityDelegator
import com.tencent.shadow.core.runtime.ShadowActivity

class PluginContainerActivity : Activity(), HostActivityDelegator {

    private var pluginActivity: ShadowActivity? = null
    private var pluginResources: Resources? = null
    private var pluginClassLoader: ClassLoader? = null
    private var pluginLayoutInflater: LayoutInflater? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS)

        if (apkPath == null || mainClass == null) {
            Log.e(TAG, "Missing apkPath or mainClass in intent")
            finish()
            return
        }

        try {
            pluginClassLoader = PluginResourceLoader.createPluginClassLoader(
                context = this,
                apkPath = apkPath,
                parentClassLoader = classLoader,
            )
            pluginResources = PluginResourceLoader.loadPluginResources(this, apkPath)
            pluginLayoutInflater = LayoutInflater.from(this).cloneInContext(object : android.content.ContextWrapper(this) {
                override fun getResources(): Resources = pluginResources!!
                override fun getClassLoader(): ClassLoader = pluginClassLoader!!
            })

            val clazz = pluginClassLoader!!.loadClass(mainClass)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is ShadowActivity) {
                pluginActivity = instance
                instance.setHostDelegator(this)
                instance.performCreate(savedInstanceState)
            } else {
                Log.e(TAG, "$mainClass is not a ShadowActivity subclass")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        pluginActivity?.performResume()
    }

    override fun onPause() {
        pluginActivity?.performPause()
        super.onPause()
    }

    override fun onStop() {
        pluginActivity?.performStop()
        super.onStop()
    }

    override fun onDestroy() {
        pluginActivity?.performDestroy()
        pluginActivity = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        pluginActivity?.onBackPressed() ?: super.onBackPressed()
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

    companion object {
        private const val TAG = "PluginContainer"
        const val EXTRA_APK_PATH = "plugin_apk_path"
        const val EXTRA_MAIN_CLASS = "plugin_main_class"

        fun createLaunchIntent(
            context: Context,
            apkPath: String,
            mainClassName: String,
        ): Intent = Intent(context, PluginContainerActivity::class.java).apply {
            putExtra(EXTRA_APK_PATH, apkPath)
            putExtra(EXTRA_MAIN_CLASS, mainClassName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
