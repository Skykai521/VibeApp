package com.tencent.shadow.core.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class ShadowActivity extends Activity {

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

    public void performCreate(Bundle savedInstanceState) {
        onCreate(savedInstanceState);
    }

    public void performResume() {
        onResume();
    }

    public void performPause() {
        onPause();
    }

    public void performStop() {
        onStop();
    }

    public void performDestroy() {
        onDestroy();
    }

    @Override
    public Resources getResources() {
        if (hostDelegator != null) return hostDelegator.getHostResources();
        return super.getResources();
    }

    @Override
    public Context getApplicationContext() {
        if (hostDelegator != null) return hostDelegator.getHostContext().getApplicationContext();
        return super.getApplicationContext();
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
