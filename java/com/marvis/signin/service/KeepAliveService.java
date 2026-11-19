package com.marvis.signin.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.marvis.signin.util.Notify;

/** 守护前台服务：通知栏常驻「AI签到助手守护中」，同时保证闹钟被系统正常投递。 */
public class KeepAliveService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startGuard();
        ScheduleManager.schedule(this);
        return START_STICKY;
    }

    private void startGuard() {
        Notification n = Notify.guardNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notify.ID_GUARD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notify.ID_GUARD, n);
            }
        } catch (Exception e) {
            try {
                startForeground(Notify.ID_GUARD, n);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
