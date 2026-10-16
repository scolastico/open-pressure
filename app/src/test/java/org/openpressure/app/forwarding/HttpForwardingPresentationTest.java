package org.openpressure.app.forwarding;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.notifications.NewReadingNotifications;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
public class HttpForwardingPresentationTest {
    @Test public void passthroughNotificationDescribesForwardingWithoutClaimingLocalHistoryWasSaved() {
        Context context=RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(context);
        HttpForwardingSettings.save(context,"https://example.invalid/measurements","Bearer synthetic",false);
        shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        HttpForwardingSettings.save(context,"https://example.invalid/measurements","",true);
        NewReadingNotifications.show(context,0,3);
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        assertEquals(1,shadowOf(manager).getAllNotifications().size());
        Notification notification=shadowOf(manager).getAllNotifications().get(0);
        assertEquals(context.getString(R.string.http_import_count,3),notification.extras.getString(Notification.EXTRA_TEXT));
        assertFalse(notification.extras.toString().contains("Bearer synthetic"));
    }
    @Test public void keepingLocalHistoryRetainsExistingSavedReadingNotification() {
        Context context=RuntimeEnvironment.getApplication();
        HttpForwardingSettings.save(context,"https://example.invalid/measurements","",true);
        shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NewReadingNotifications.show(context,1,0);
        Notification notification=shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().get(0);
        assertEquals(context.getResources().getQuantityString(R.plurals.notice_readings_saved,1,1),notification.extras.getString(Notification.EXTRA_TEXT));
    }
}
