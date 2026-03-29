package com.tencent.shadow.core.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public interface HostActivityDelegator {
    Context getHostContext();
    Resources getHostResources();
    Resources.Theme getHostTheme();
    LayoutInflater getHostLayoutInflater();
    Window getHostWindow();
    WindowManager getHostWindowManager();
    ClassLoader getPluginClassLoader();
    void superSetContentView(int layoutResID);
    void superSetContentView(View view);
    <T extends View> T superFindViewById(int id);
    void superStartActivity(Intent intent);
    void superFinish();
    void setPluginResult(int resultCode, Intent data);
    Intent getHostIntent();
}
