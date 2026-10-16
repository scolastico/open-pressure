package org.openpressure.app.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.openpressure.app.MainActivity;
import org.openpressure.app.R;

/** Count-only automatic-import alerts, separate from the foreground connection notification. */
public final class NewReadingNotifications {
    public static final String CHANNEL_ID="new-readings";
    public static final String EXTRA_OPEN_HISTORY="open_history";
    public static final int PERMISSION_REQUEST=74;
    private static final int NOTIFICATION_ID=2;

    private NewReadingNotifications() {}

    public static boolean permissionRequested(Context context) {
        return context.getSharedPreferences("reading-notifications",Context.MODE_PRIVATE).getBoolean("permission_requested",false);
    }
    public static void requestPermission(Activity activity) {
        if(Build.VERSION.SDK_INT<33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) return;
        activity.getSharedPreferences("reading-notifications",Context.MODE_PRIVATE).edit().putBoolean("permission_requested",true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);
    }
    public static void requestPermissionIfNeeded(Activity activity) {
        if(!permissionRequested(activity)) requestPermission(activity);
    }

    public static void createChannel(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null) return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,context.getString(R.string.notice_readings_channel),NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notice_readings_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    public static boolean enabled(Context context) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL_ID);
        return channel==null || channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }

    public static void show(Context context,int stored,int queued) {
        if(stored<=0 && queued<=0) return;
        try {
            createChannel(context);
            if(!enabled(context)) return;
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(manager==null) return;
            Intent intent=new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_HISTORY,true);
            PendingIntent open=PendingIntent.getActivity(context,NOTIFICATION_ID,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            String text=stored>0?context.getResources().getQuantityString(R.plurals.notice_readings_saved,stored,stored):"";
            if(queued>0) text+=(text.isEmpty()?"":"\n\n")+context.getString(R.string.http_import_count,queued);
            Notification publicVersion=new Notification.Builder(context,CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_pressure)
                    .setContentTitle(context.getString(R.string.notice_app_name))
                    .setContentText(text)
                    .build();
            Notification notification=new Notification.Builder(context,CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_pressure)
                    .setContentTitle(context.getString(R.string.notice_app_name))
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIFICATION_ID,notification);
        } catch(SecurityException ignored) {
            // Permission can be revoked between the check and notify. Importing must still finish.
        }
    }
}
