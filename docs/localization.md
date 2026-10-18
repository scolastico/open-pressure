# Languages

OpenPressure offers 52 language/region options, including two Chinese writing systems. The app name **OpenPressure** and product name **Health Connect** remain unchanged in every language.

English, German, Spanish, French, Italian, Portuguese, Russian, Ukrainian, Polish, Dutch, Swedish, Norwegian Bokmål, Danish, Finnish, Czech, Slovak, Hungarian, Romanian, Bulgarian, Greek, Turkish, Arabic, Hebrew, Persian, Urdu, Hindi, Bengali, Punjabi, Gujarati, Marathi, Tamil, Telugu, Kannada, Malayalam, Odia, Nepali, Sinhala, Thai, Vietnamese, Indonesian, Malay, Filipino, Swahili, Simplified Chinese, Traditional Chinese, Japanese, Korean, Burmese, Amharic, Hausa, Yoruba and Zulu are included.

On Android 13 or newer, open **About → App language** to choose the app language in Android settings. On older Android versions, the app follows the phone language; **Phone language** opens the system settings. English is the fallback for unsupported locales.

Translations began with an automated baseline. German received a contextual review throughout; navigation and other key actions received additional contextual corrections across locales. This is not native-speaker review of every sentence. Corrections from speakers of each language are welcome, particularly around consent, deletion and device setup. Translation availability does not establish monitor compatibility.

## Maintaining translations

Default resources are in `app/src/main/res/values/strings*.xml`. Locale overlays are in the corresponding `values-*` directories, and `res/xml/locales_config.xml` lists the selectable locales. Keep brand-only resources non-translatable; do not add localized replacements for them. Preserve format arguments such as `%1$s` and `%1$d`, and translate their surrounding meaning in context. “Readings” means blood pressure measurements, “monitor” means a blood pressure device, and “Close” dismisses a screen.

Run:

```sh
python3 tools/check-locales.py
./gradlew :app:assembleDebug :app:lintDebug
```

The locale checker verifies complete resource coverage, unchanged format arguments, invariant product names and required plural categories. `tools/locale-plurals.json` records the union of Android lint's API 26 and API 36 plural categories, matching the minimum supported Android version and current compile SDK. These checks cannot judge translation quality. Review changes in context on a device or emulator, including long text, larger font settings and right-to-left layouts.

Clock-update and transfer-confirmation messages live in `strings_monitor_import.xml` for all 52 locale options. These additions received automated/contextual translation without native-speaker review. Service integration tests check warning selection with English, German and Arabic resources, including after connection cleanup. Device layout, larger-font and native-speaker review remain necessary; resource and callback tests do not establish visual or linguistic quality.

HTTP forwarding settings/status text lives in `strings_http_forwarding.xml`. The matching About/privacy paragraphs describe optional configured network forwarding and temporary queued storage in passthrough. These additions also use automated/contextual translations without native-speaker review; resource checks do not establish screen layout or language quality.

Measurement reminder settings and notifications live in `strings_reminders.xml` for all 52 locale options. Times and weekday names follow Android's locale and time-format settings. These additions received automated/contextual translation; native-speaker and physical layout review remain unperformed.

Device-report and update-check text lives in `strings_device_report.xml` and `strings_updates.xml` for all 52 locale options. Button names and menu paths inside sentences are format arguments, so they always match the localized labels. These additions received automated translation without native-speaker review.

Virtual user settings, ownership prompts, pending-count notifications and named-profile Health Connect text live in `strings_virtual_users.xml`. All 52 options have complete resource coverage and preserved placeholders. Translations received automated/contextual review, including German and Arabic corrections; native-speaker and visual layout review remain unperformed. Keep person names separate from the monitor's physical memory-bank labels.

Sync clock text (the button, its confirmation dialog, the clock-sync outcomes and the device-report offer after a clock write on an untested monitor) is defined in `strings_monitor.xml`, and the clock-sync backup card in `strings_main.xml`; locale overlays live in `strings_clock_sync.xml` for all 52 locale options. The difference between the monitor and phone clocks is a seconds plural. The privacy paragraph of the report offer reuses each locale's existing device-report wording. These additions received automated translation without native-speaker review; resource checks do not establish screen layout or language quality.

Developer options text (version label, unlock hints, write-aspect switch, its confirmation and the write-support viewer) lives in `strings_developer.xml` for all 52 locale options. These additions received automated translation without native-speaker review; resource checks do not establish screen layout or language quality. Index evidence notes are shown as technical left-to-right text and are not translated.
