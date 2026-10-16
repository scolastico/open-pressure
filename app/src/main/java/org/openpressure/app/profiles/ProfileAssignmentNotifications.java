package org.openpressure.app.profiles;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.openpressure.app.R;

/** Count-only alerts for measurements safely retained in the assignment inbox. */
public final class ProfileAssignmentNotifications {
    public static final String CHANNEL_ID="measurement-assignment";
    private ProfileAssignmentNotifications() { }
    private static final int NOTIFICATION_ID=3;
    public static boolean enabled(Context context) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL_ID);
        return channel==null || channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    public static void createChannel(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null) return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,context.getString(R.string.vp_pending_channel),NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.vp_pending_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); manager.createNotificationChannel(channel);
    }
    public static void cancel(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager!=null) manager.cancel(NOTIFICATION_ID);
    }
    public static void show(Context context,int pending) {
        if(pending<1) { cancel(context); return; }
        try {
            createChannel(context); if(!enabled(context)) return;
            Intent intent=new Intent(context,AssignmentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent open=PendingIntent.getActivity(context,NOTIFICATION_ID,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(context,CHANNEL_ID).setSmallIcon(R.drawable.ic_pressure)
                    .setContentTitle(context.getString(R.string.vp_pending_notification_title))
                    .setContentText(context.getString(R.string.vp_pending_notification_text,pending))
                    .setStyle(new Notification.BigTextStyle().bigText(context.getString(R.string.vp_pending_notification_text,pending)))
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(manager!=null) manager.notify(NOTIFICATION_ID,notification);
        } catch(SecurityException revoked) { /* Permission loss never consumes pending data. */ }
    }
}
