package com.fastshare.app;

import android.app.Application;
import android.content.Context;

public class FastShareApp extends Application {
    private ShareHub hub;

    @Override
    public void onCreate() {
        super.onCreate();
        hub = new ShareHub(this);
        try {
            BackupWorker.schedule(this);
        } catch (Exception ignored) { }      // never let scheduling stop the app from opening
    }

    static ShareHub hub(Context c) {
        return ((FastShareApp) c.getApplicationContext()).hub;
    }
}
