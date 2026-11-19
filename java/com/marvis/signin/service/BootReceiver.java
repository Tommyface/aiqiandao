package com.marvis.signin.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.net.SignEngine;
import com.marvis.signin.util.TimeUtils;

import java.util.List;

/** 开机 / 应用更新后：重设闹钟，并把当天漏掉的签补上。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, final Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        ScheduleManager.schedule(app);

        if (!TimeUtils.passedTodayAt(new Store(app).getHour(), new Store(app).getMinute())) {
            pr.finish();
            return;
        }
        final Store store = new Store(app);
        List<Platform> all = store.getPlatforms();
        SignEngine.signPending(app, all, new SignEngine.Callback() {
            @Override
            public void onOne(Platform p, boolean success, String message) {
            }

            @Override
            public void onAllDone(int success, int total) {
                pr.finish();
            }
        });
    }
}
