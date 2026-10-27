package com.glass.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationListener extends NotificationListenerService {
    static volatile NotificationListener instance;
    static volatile Runnable onChange;

    @Override public void onListenerConnected() { instance = this; ping(); }
    @Override public void onListenerDisconnected() { if (instance == this) instance = null; ping(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { ping(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { ping(); }

    private void ping() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
