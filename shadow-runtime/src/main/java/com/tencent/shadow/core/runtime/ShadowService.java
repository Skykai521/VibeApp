package com.tencent.shadow.core.runtime;

import android.app.Service;

public class ShadowService extends Service {
    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
