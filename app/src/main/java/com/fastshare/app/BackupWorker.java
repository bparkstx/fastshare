package com.fastshare.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** The scheduled photo backup: runs on Wi-Fi about every six hours and sends only what is new. */
public class BackupWorker extends Worker {
    static final String UNIQUE_NAME = "filebandit-photo-backup";

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!new Prefs(getApplicationContext()).backupOn()) return Result.success();
        BackupJob.Outcome o = BackupJob.run(getApplicationContext(), new Backup.Listener() {
            @Override public void file(String name, int index, int total) { }
            @Override public void bytes(long n) { }
            @Override public boolean cancelled() { return isStopped(); }
        });
        if (o.alreadyRunning) return Result.success();
        // the server isn't reachable (away from home): try again later, the schedule's back-off handles the timing
        if (o.connectionFailed) return Result.retry();
        return Result.success();
    }

    /** Makes the schedule match the settings. Call after any change, and on app start. */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        Prefs p = new Prefs(c);
        WorkManager wm = WorkManager.getInstance(c);
        if (!p.backupOn()) {
            wm.cancelUniqueWork(UNIQUE_NAME);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)          // Wi-Fi, never mobile data
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(p.backupCharging())
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(BackupWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build();
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req);
    }
}
