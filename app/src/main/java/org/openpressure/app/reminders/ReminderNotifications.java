package org.openpressure.app.reminders;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.openpressure.app.MainActivity;
import org.openpressure.app.R;

/** Generic phone reminders on their own optional channel, without measurement details. */
public final class ReminderNotifications {
    public static final String CHANNEL_ID="measurement-reminders";
    private static final String TAG="measurement-reminder";
    private ReminderNotifications() {}
    public static void createChannel(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null) return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,context.getString(R.string.reminder_channel),NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
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
    static void cancel(Context context,int id) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager!=null) manager.cancel(TAG,id);
    }
    static void show(Context context,int id) {
        try {
            createChannel(context);
            if(!enabled(context)) return;
            Intent intent=new Intent(context,MainActivity.class).setData(Uri.parse("openpressure://reminder/open/"+id))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_reminders_overview",true);
            PendingIntent open=PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(context,CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_pressure).setContentTitle(context.getString(R.string.reminder_notification_title))
                    .setContentText(context.getString(R.string.reminder_notification_text)).setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build();
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(manager!=null) manager.notify(TAG,id,notification);
        } catch(SecurityException revoked) { /* Permission can be revoked immediately before notify. */ }
    }
}
