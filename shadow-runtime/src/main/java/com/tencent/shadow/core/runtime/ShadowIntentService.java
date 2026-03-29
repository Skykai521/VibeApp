package com.tencent.shadow.core.runtime;

import android.app.IntentService;

public class ShadowIntentService extends IntentService {
    public ShadowIntentService() {
        super("ShadowIntentService");
    }

    public ShadowIntentService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(android.content.Intent intent) {
    }
}
