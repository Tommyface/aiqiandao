package com.marvis.signin.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.marvis.signin.data.Store;
import com.marvis.signin.util.TimeUtils;

/** 统一维护每日签到闹钟。 */
public class ScheduleManager {

    public static final String ACTION_DAILY = "com.marvis.signin.action.DAILY_CHECKIN";
    private static final int REQ = 2001;

    private static PendingIntent intent(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_DAILY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Store store = new Store(c);
        long next = TimeUtils.nextTrigger(store.getHour(), store.getMinute());
        PendingIntent pi = intent(c);
        am.cancel(pi);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, next, pi);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(intent(c));
    }
}
