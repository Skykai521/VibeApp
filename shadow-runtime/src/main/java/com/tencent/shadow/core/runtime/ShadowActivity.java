package com.tencent.shadow.core.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Base class for all generated Activities.
 *
 * Extends AppCompatActivity so generated apps get full Material/AppCompat support.
 *
 * <ul>
 *   <li><b>Standalone mode</b> (hostDelegator == null): behaves as a normal
 *       AppCompatActivity — all calls go to super.</li>
 *   <li><b>Plugin mode</b> (hostDelegator != null): resource/view/navigation
 *       calls are redirected to the host PluginContainerActivity. Lifecycle
 *       super calls still run because the host container is a real
 *       AppCompatActivity that provides the AppCompat environment.</li>
 * </ul>
 */
public class ShadowActivity extends AppCompatActivity {

    private HostActivityDelegator hostDelegator;

    public void setHostDelegator(HostActivityDelegator delegator) {
        this.hostDelegator = delegator;
    }

    public HostActivityDelegator getHostDelegator() {
        return hostDelegator;
    }

    public boolean isPluginMode() {
        return hostDelegator != null;
    }

    // --- Plugin lifecycle entry points (called by PluginContainerActivity) ---
    // In plugin mode, the ShadowActivity is not started by the system.
    // These methods are called by the host container to drive the plugin lifecycle.
    // We do NOT call super lifecycle methods here because the plugin Activity
    // was never attached to the Android Activity system (no Application, no
    // ActivityThread registration). The host container handles the real lifecycle.

    public void performCreate(Bundle savedInstanceState) {
        if (hostDelegator != null) {
            // Attach the host context so ALL ContextWrapper methods work
            // (getSystemService, getOpPackageName, getTheme, etc.).
            // This is safe because the Activity was never system-attached in
            // plugin mode (mBase is null). AppCompatDelegate gets partially
            // initialized (night-mode context wrapping) but its heavy setup
            // (Factory2, SubDecor) only happens in onCreate(), which we skip.
            attachBaseContext(hostDelegator.getHostContext());
        }
        onPluginCreate(savedInstanceState);
    }

    public void performResume() {
        onPluginResume();
    }

    public void performPause() {
        onPluginPause();
    }

    public void performStop() {
        onPluginStop();
    }

    public void performDestroy() {
        onPluginDestroy();
    }

    /**
     * Called in plugin mode instead of onCreate. Subclasses override onCreate
     * as normal — this method calls it only when NOT in plugin mode (standalone).
     * In plugin mode, the subclass's onCreate is called directly.
     */
    protected void onPluginCreate(Bundle savedInstanceState) {
        // Default: call the subclass's onCreate which may call super.onCreate.
        // In plugin mode, super.onCreate (this class) will be a no-op.
        pluginLifecycleActive = true;
        onCreate(savedInstanceState);
        pluginLifecycleActive = false;
    }

    protected void onPluginResume() {
        pluginLifecycleActive = true;
        onResume();
        pluginLifecycleActive = false;
    }

    protected void onPluginPause() {
        pluginLifecycleActive = true;
        onPause();
        pluginLifecycleActive = false;
    }

    protected void onPluginStop() {
        pluginLifecycleActive = true;
        onStop();
        pluginLifecycleActive = false;
    }

    protected void onPluginDestroy() {
        pluginLifecycleActive = true;
        onDestroy();
        pluginLifecycleActive = false;
    }

    // Flag: when true, lifecycle super calls are skipped
    private boolean pluginLifecycleActive;

    // --- Lifecycle overrides: skip super in plugin mode ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!pluginLifecycleActive) {
            super.onCreate(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        if (!pluginLifecycleActive) {
            super.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (!pluginLifecycleActive) {
            super.onPause();
        }
    }

    @Override
    protected void onStop() {
        if (!pluginLifecycleActive) {
            super.onStop();
        }
    }

    @Override
    protected void onDestroy() {
        if (!pluginLifecycleActive) {
            super.onDestroy();
        }
    }

    // --- AppCompat overrides: no-op in plugin mode ---
    // In plugin mode the Activity was never system-attached (no attachBaseContext),
    // so AppCompatDelegate has no context and any call into it will NPE.
    // These overrides prevent generated plugin code from triggering that path.

    @Override
    public void setSupportActionBar(Toolbar toolbar) {
        if (hostDelegator != null) return; // no-op in plugin mode
        super.setSupportActionBar(toolbar);
    }

    @Override
    public ActionBar getSupportActionBar() {
        if (hostDelegator != null) return null;
        return super.getSupportActionBar();
    }

    // --- Plugin-specific overrides ---
    // mBase is now set via attachBaseContext in performCreate, so most
    // ContextWrapper methods work automatically. These overrides redirect
    // to plugin-specific resources / inflater / ClassLoader.

    @Override
    public Resources getResources() {
        if (hostDelegator != null) return hostDelegator.getHostResources();
        return super.getResources();
    }

    @Override
    public Resources.Theme getTheme() {
        if (hostDelegator != null) return hostDelegator.getHostTheme();
        return super.getTheme();
    }

    @Override
    public Context getApplicationContext() {
        if (hostDelegator != null) return hostDelegator.getHostContext().getApplicationContext();
        return super.getApplicationContext();
    }

    @Override
    public Object getSystemService(String name) {
        if (hostDelegator != null) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                return hostDelegator.getHostLayoutInflater();
            }
            // Activity.getSystemService returns mWindowManager directly for
            // WINDOW_SERVICE, but mWindowManager is null because the system
            // never called Activity.attach(). Delegate to host.
            if (WINDOW_SERVICE.equals(name)) {
                return hostDelegator.getHostWindowManager();
            }
        }
        return super.getSystemService(name);
    }

    @Override
    public LayoutInflater getLayoutInflater() {
        if (hostDelegator != null) return hostDelegator.getHostLayoutInflater();
        return super.getLayoutInflater();
    }

    @Override
    public void setContentView(int layoutResID) {
        if (hostDelegator != null) { hostDelegator.superSetContentView(layoutResID); return; }
        super.setContentView(layoutResID);
    }

    @Override
    public void setContentView(View view) {
        if (hostDelegator != null) { hostDelegator.superSetContentView(view); return; }
        super.setContentView(view);
    }

    @Override
    public <T extends View> T findViewById(int id) {
        if (hostDelegator != null) return hostDelegator.superFindViewById(id);
        return super.findViewById(id);
    }

    @Override
    public Window getWindow() {
        if (hostDelegator != null) return hostDelegator.getHostWindow();
        return super.getWindow();
    }

    @Override
    public WindowManager getWindowManager() {
        if (hostDelegator != null) return hostDelegator.getHostWindowManager();
        return super.getWindowManager();
    }

    @Override
    public void startActivity(Intent intent) {
        if (hostDelegator != null) { hostDelegator.superStartActivity(intent); return; }
        super.startActivity(intent);
    }

    @Override
    public void finish() {
        if (hostDelegator != null) { hostDelegator.superFinish(); return; }
        super.finish();
    }

    @Override
    public Intent getIntent() {
        if (hostDelegator != null) return hostDelegator.getHostIntent();
        return super.getIntent();
    }

    @Override
    public ClassLoader getClassLoader() {
        if (hostDelegator != null) return hostDelegator.getPluginClassLoader();
        return super.getClassLoader();
    }

    @Override
    public String getPackageName() {
        if (hostDelegator != null) return hostDelegator.getHostContext().getPackageName();
        return super.getPackageName();
    }
}
