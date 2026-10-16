# Optional HTTP forwarding

Open **Devices → Advanced → HTTP forwarding**. Enter the full destination URL and, if needed, the value of the `Authorization` header, such as `Bearer your-token`. Save to enable forwarding. Leave the URL empty and save to turn it off. The feature is off by default and does not require an OpenPressure account or hosted service.

Use an endpoint you control or trust. HTTPS uses Android's normal certificate and hostname verification. Plain HTTP is supported for servers that require it, including local-network receivers, but sends measurements and authorization without transport encryption. Redirect responses are not followed; configure the final destination URL.

## Local history and passthrough

**Keep new readings in local history** is on by default and is available when a destination is configured. With it on, new readings enter normal local history and the independent upload queue. Existing history is not automatically uploaded when forwarding is enabled.

Turn it off for passthrough: new Bluetooth and manual measurements enter a temporary private upload queue instead of local history. A successful server acknowledgment removes that queued payload. These readings do not appear in local trends, CSV/PDF reports or Health Connect. Raw packet diagnostics are also suppressed while passthrough is active, so they cannot become a second retained copy of measurements. Saved monitors, pairing keys, existing readings and explicitly requested safety backups remain intact.

Import summaries and background alerts report the actual number of readings stored locally and accepted for forwarding. Those outcomes do not change if you edit settings afterward; an import spanning a storage-setting change can report both. Acceptance for forwarding means the reading entered the durable queue, not that the server has already acknowledged it.

Passthrough permits temporary on-device persistence for delivery reliability. It is not a promise that measurements never touch disk or that deleting an acknowledged payload securely erases storage media. Hash-only receipts remain to prevent repeated cuff history from uploading the same measurement again. Disabling forwarding restores local storage for future readings; it does not delete existing data or queued payloads.

Optional [measurement reminders](reminders.md) retain only minimal measurement timing and occurrence skip markers for recent-measurement suppression, including after an upload is acknowledged. They do not add local history or another copy of measurement values.

## Delivery and retries

The app queues readings transactionally with their optional local-history insertion. Uploads run off the UI/Bluetooth thread through WorkManager, with network constraints, backoff and periodic recovery. Server errors and unavailable networks retain the queued payload for retry. Android power management, network availability and process scheduling can delay delivery.

Only an HTTP `2xx` response acknowledges a measurement. Other responses, including redirects and authorization failures, keep it pending. **Retry pending uploads** requests another attempt using the saved configuration. The settings screen shows the pending count for the currently configured server and a general failure message, without displaying response bodies or credentials.

Queued readings stay assigned to their original URL. Changing the destination pauses uploads for the previous URL; it does not silently send that backlog elsewhere. Restoring the previous URL resumes its queue. Updating authorization for the same URL applies to future attempts. Disabling forwarding pauses delivery. A request already dispatched when settings change may still finish.

Delivery is at least once: an interrupted acknowledgment can cause the same request to be retried. The stable `Idempotency-Key` header and JSON `id` let the receiver deduplicate it. The receiver must implement that deduplication if duplicate side effects matter. Repeated stored cuff records retain their identity even when their measurement time is unavailable; undated live packets remain separate events, following the app's existing identity rules.

## Receiver contract

Each request is a `POST` to the configured URL, with `Content-Type: application/json`, an `Idempotency-Key` and the optional `Authorization` value exactly as configured. The JSON object contains:

| Field | Meaning |
| --- | --- |
| `schema_version` | Integer `1` |
| `id` | Stable SHA-256 event identity, also used for `Idempotency-Key` |
| `device_id` | SHA-256 device identifier; no clear Bluetooth address |
| `source` | Import source, such as `vendor-history`, `wlp-history`, `bluetooth`, `history` or `manual` |
| `user` | Monitor user bank, or `null` if unavailable |
| `measured_at` | Original device/manual local wall time, or `null`; no invented timezone or receipt-time substitution |
| `received_at` | Time received by the app, in UTC |
| `systolic`, `diastolic` | Numeric pressure values in the stated original unit |
| `mean_arterial_pressure` | Numeric value in the same unit, or `null` |
| `pulse` | Beats per minute, or `null` |
| `unit` | `mmHg` or `kPa` |
| `status` | Measurement status bits, or `null` |
| `atrial_fibrillation` | Separate boolean AFib flag, or `null`; an irregular-pulse flag does not imply AFib |

Raw Bluetooth packets, notes, pairing keys and authorization values are not part of the JSON payload. Both user banks can be forwarded; Health Connect's separate one-bank selection does not filter this feature. Changing a local note does not generate a new measurement upload.

## Privacy and validation

Configuration, credentials and pending payloads use private app storage; Android app backup/device transfer remain disabled. Authorization is not included in diagnostic exports, WorkManager input or measurement JSON. Your server's storage, access and retention policies apply after delivery. Local deletion, disabling forwarding or uninstalling the app cannot delete server copies. Uninstalling removes local pending uploads as well as other local app data.

The upload queue is independent of local history. Deleting a local history entry does not cancel an upload already queued for it. Clear the destination to pause forwarding; queued payloads remain assigned to their original destination until it accepts them or the app's local data is removed.

This feature does not change commands sent to a blood pressure monitor. Automated checks use synthetic measurements and a loopback HTTP receiver. They cover settings validation, header injection, delivery/receipt handling, retry identity, storage isolation, migration and service callbacks. Actual server interoperability, background timing on a phone and translated screen layouts require device validation.
