package com.marvis.signin.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.marvis.signin.MainActivity;
import com.marvis.signin.R;

public class Notify {

    public static final String CH_GUARD = "ch_guard";
    public static final String CH_RESULT = "ch_result";
    public static final int ID_GUARD = 1001;
    public static final int ID_RESULT = 1002;

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel guard = new NotificationChannel(
                CH_GUARD, c.getString(R.string.guard_channel), NotificationManager.IMPORTANCE_MIN);
        guard.setShowBadge(false);
        guard.setDescription("签到守护常驻通知");
        nm.createNotificationChannel(guard);

        NotificationChannel result = new NotificationChannel(
                CH_RESULT, c.getString(R.string.result_channel), NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription("每日签到结果");
        nm.createNotificationChannel(result);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    @SuppressWarnings("deprecation")
    public static Notification guardNotification(Context c) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_GUARD);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(c.getString(R.string.guard_title))
                .setContentText(c.getString(R.string.guard_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c));
        return b.build();
    }

    @SuppressWarnings("deprecation")
    public static void result(Context c, String title, String text) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_RESULT);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(c));
        try {
            nm.notify(ID_RESULT, b.build());
        } catch (Exception ignored) {
        }
    }
}
