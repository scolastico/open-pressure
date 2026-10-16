package org.openpressure.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Explicit acceptance of the current experimental-device warning. */
public final class ExperimentalConsent {
    private static final int VERSION=1;
    private ExperimentalConsent() {}
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("experimental_consent",Context.MODE_PRIVATE);
    }
    public static boolean accepted(Context context) {
        return preferences(context).getInt("accepted_version",0)==VERSION;
    }
    public static boolean accept(Context context) {
        SharedPreferences preferences=preferences(context);
        if(preferences.edit().putInt("accepted_version",VERSION).commit()) return true;
        // A failed commit still updates the in-memory preferences; do not unlock on that value.
        preferences.edit().remove("accepted_version").commit();
        return false;
    }
}
