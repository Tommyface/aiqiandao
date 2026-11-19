package com.marvis.signin.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.net.SignEngine;
import com.marvis.signin.util.Notify;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 到点触发：执行漏签补做，然后预约下一次。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        final PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl =
                pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signin:alarm");
        if (wl != null) {
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60 * 1000L);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int total = 0;
                try {
                    Store store = new Store(app);
                    List<Platform> all = store.getPlatforms();
                    final CountDownLatch latch = new CountDownLatch(1);
                    final int[] res = new int[]{0, 0};
                    SignEngine.signPending(app, all, new SignEngine.Callback() {
                        @Override
                        public void onOne(Platform p, boolean success, String message) {
                        }

                        @Override
                        public void onAllDone(int success, int t) {
                            res[0] = success;
                            res[1] = t;
                            latch.countDown();
                        }
                    });
                    latch.await(150, TimeUnit.SECONDS);
                    ok = res[0];
                    total = res[1];
                    if (total > 0) {
                        String text = ok == total
                                ? "今日 " + total + " 个平台全部签到成功"
                                : "今日 " + ok + "/" + total + " 个平台签到成功，点开看看";
                        Notify.result(app, "AI签到助手", text);
                    }
                } catch (Exception ignored) {
                } finally {
                    ScheduleManager.schedule(app);
                    if (wl != null && wl.isHeld()) {
                        wl.release();
                    }
                    pr.finish();
                }
            }
        }).start();
    }
}
