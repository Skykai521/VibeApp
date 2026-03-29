package com.tencent.shadow.core.runtime;

import android.app.Application;
import android.content.Context;

public class ShadowApplication extends Application {

    private Context hostContext;

    public void setHostContext(Context context) {
        this.hostContext = context;
    }

    public boolean isPluginMode() {
        return hostContext != null;
    }

    @Override
    public Context getApplicationContext() {
        if (hostContext != null) return hostContext.getApplicationContext();
        return super.getApplicationContext();
    }
}
