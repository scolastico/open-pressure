# Measurement reminders

Open **Measurement reminders** at the bottom of Overview to add a reminder. Choose an hour and minute using the phone's time picker, then select the weekdays on which it should repeat. With no weekdays selected, it runs once at the next occurrence of that time. Each reminder can be edited, enabled, disabled or deleted independently.

These are phone notifications. They do not configure alarms on the monitor, start a measurement or send any commands to a device. They use the phone's local time and follow its timezone. Swiping away a notification dismisses that occurrence; a repeating reminder remains enabled. A one-time reminder turns off after its occurrence, including when that occurrence is skipped.

## Skip after a recent measurement

Each reminder has a **Skip if measured in the previous minutes** field. The default is **120 minutes (2 hours)**. Set it to **0** to disable skipping. A qualifying measurement skips only that occurrence; it does not disable later repeating reminders.

A newly accepted Bluetooth or manual measurement from any user bank can qualify. Its measurement time must fall within the configured interval before the reminder's scheduled time, including the interval's boundaries. Importing old history does not make it recent. Dated measurements use their original wall time, interpreted in the phone's timezone because monitor records contain no timezone. Future-dated measurements are ignored. A live Bluetooth reading without a device time uses the time the app received it; an undated stored history record does not qualify. The app must receive the measurement before handling the reminder. Measurements that remain only on the cuff cannot suppress a phone notification.

After an upgrade, existing valid dated local history can supply the latest measurement time. This does not upload that history or create additional readings.

The suppression check uses the scheduled time even if Android delivers the notification late. A measurement taken after that scheduled time does not qualify. Deleting local history does not reset an already recorded suppression decision.

## Android permissions and timing

Allow reminder notifications when requested. If notifications or the **Measurement reminders** channel are blocked, the screen offers access to Android's notification controls. This channel is separate from connection and new-reading notifications; Android controls its sound, vibration and Do Not Disturb behavior. The reminder is a normal dismissible notification, not a full-screen or continuously ringing alarm.

On Android 12 and newer, **Allow precise reminders** opens the system's Alarms & reminders access settings. When this access is available, OpenPressure schedules exact alarms; otherwise it uses Android's inexact scheduling and explains that delivery can be late. If you revoke this access, reopen OpenPressure so it can restore inexact scheduling. Android can cancel exact alarms when access is revoked. See Android's [alarm scheduling documentation](https://developer.android.com/develop/background-work/services/alarms) and [notification permission documentation](https://developer.android.com/develop/ui/views/notifications/notification-permission).

Reminders are rescheduled after reboot, app replacement and phone clock/timezone changes. Past occurrences missed while the phone was off are not replayed. A local time skipped by daylight saving moves forward by the clock change; a repeated local time runs once for that date. Android power management, notification settings and force-stopping the app can prevent or delay alerts. Reopen the app after force-stopping it.

## Privacy and validation

Reminder configuration stays in private app preferences. To support suppression in both ordinary storage and HTTP passthrough, the app keeps minimal timing metadata: the latest qualifying measurement time and skip decisions for scheduled occurrences. It does not keep another copy of blood pressure values, packets, user banks, device identifiers or notes. This timing metadata can remain after local history deletion or successful upload. Reminder notifications contain no measurement values or device identifiers.

Automated tests use synthetic measurements, SQLite, simulated Android alarms and notification inspection. These do not establish alarm timing on a physical phone, vendor power-management behavior, sound/vibration, translation quality or screen layout. Device checks are listed in [hardware validation](hardware-validation.md#measurement-reminders).
