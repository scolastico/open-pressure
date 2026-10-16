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
