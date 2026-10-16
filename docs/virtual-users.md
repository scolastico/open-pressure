# Optional virtual users

Open **Virtual users** at the bottom of Overview to create named profiles and turn on **Use virtual users**. The feature is off by default. Create as many profiles as you need; at least one must remain while the feature is enabled. Names can be changed later. Removing a profile requires choosing another profile to receive its saved measurements; it does not delete those measurements.

When enabled, named profiles replace the usual User 1/User 2 choices in Overview, History and exports. The monitor's memory banks remain separate technical identifiers. Where registration requires a bank, the app labels it **Monitor memory bank**. A virtual profile is never used as a monitor registration number or sent to the cuff. This feature does not change device commands or monitor configuration.

## Assigning measurements

Each new Bluetooth or manual measurement waits in a durable private assignment queue. While an app screen is open, the app opens **Who took this measurement?**. It shows the oldest pending measurement, including its time and pressure values, and asks you to choose a named user. After a successful assignment, it moves to the next pending measurement. Original measurement time determines the order; unavailable times use receipt time, and tied times use arrival order.

Choose **Later** or go back to leave the queue intact. It will be offered again on the next foreground session, or when another new measurement arrives. Pending measurements are also accessible through **Assign pending measurements** on Overview. Editing profiles does not interrupt you with an assignment picker; returning to the main screen resumes the pending work.

In the background, the **Measurement assignment** notification channel shows how many measurements need a user. Tapping it opens the same oldest-first picker directly. Swiping away or ignoring the notification does not remove readings. If Android blocks notifications, the next foreground session still opens the picker. Notification permission and channel controls are available in Virtual users settings. Notifications contain no pressure values, names, device identifiers or notes.

Pending measurements survive app/process restarts and accumulate without requiring a notification to remain visible. They are removed from the queue only after assignment commits successfully. A storage failure leaves the current measurement pending so you can retry. Repeated imports of the same stored measurement do not create another assignment prompt. Distinct undated live packets retain the app's existing separate-event identity rule.

A reading forwarded before virtual users were enabled can remain an ordinary duplicate at its original destination. Sending that still-unassigned reading to a different destination requires choosing its owner first.

## History and exports

Existing history is preserved when enabling this feature; it is not automatically assigned from monitor bank numbers. Older readings without a virtual profile appear as **Unassigned** in the all-users view. New assigned readings can be filtered by name in Overview and History, including trends, CSV/PDF export and counted local deletion. Renaming a profile updates local labels. Removing a profile reassigns its local measurements and saved assignment identities to the explicitly selected replacement.

Turning virtual users off preserves profiles, assignments and pending measurements. Future readings resume ordinary monitor-bank behavior, while existing pending work still requires assignment. Re-enabling restores the named profiles. Local history deletion and profile changes do not delete copies already written to Health Connect or sent to an HTTP server.

**Health Connect** requires selecting your own named user and enabling export again after changing between monitor-bank and virtual-user modes. Only assigned local readings for that selected person are eligible. Switching modes, deleting the selected profile or losing permission prevents further exports until a valid explicit selection and consent exist. There is no all-users Health Connect export.

**HTTP forwarding** waits until assignment. Each pending measurement retains the destination and local-storage choice that applied when it arrived. A later settings change does not reroute the queued measurement or change whether it enters history. Assigned uploads include the profile's local ID and name in addition to the monitor bank. Names in an already queued/sent payload are a snapshot at assignment; renaming or removing a local profile does not update server copies. Delivery remains bound to the captured URL and the normal acknowledgment/retry policy.

In passthrough mode, pending measurements use temporary private storage until assignment and server acceptance. They do not enter local history or Health Connect. This queue contains the data needed for assignment and delivery; it does not retain raw packets or notes. Hash-only assignment receipts with the selected profile ID remain after delivery to avoid repeated prompts and preserve known ownership on a later deliberate reimport. Existing pairing material and explicit safety backups retain their separate documented policies.

## Validation

Synthetic tests exercise SQLite upgrades, multiple named users, minimum-one enforcement, explicit reassignment on removal, FIFO ordering, process restart, duplicate imports, rollback, mode changes, foreground/background routing, ignored/blocked notifications, passthrough and export isolation. Service tests exercise actual Bluetooth callback orchestration with simulated peer packets; no new device commands are introduced. Physical-phone notification/activity behavior, Health Connect interoperability, native-speaker translation quality and long/RTL/large-font layouts remain to be checked on devices.
