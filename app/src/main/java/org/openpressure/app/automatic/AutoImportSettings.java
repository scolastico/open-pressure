package org.openpressure.app.automatic;

import android.content.Context;
import android.content.SharedPreferences;

/** Automatic reading imports are independent of Health Connect export. */
public final class AutoImportSettings {
    public static final int OFF=0, VISIBLE=1, NOTIFICATION=2, COMPANION=3;
    public static final String PREFERENCES="automatic-imports", MODE_KEY="mode";
    private AutoImportSettings() {}
    public static int getMode(Context context) {
        int mode=preferences(context).getInt(MODE_KEY,VISIBLE);
        return mode>=OFF && mode<=COMPANION?mode:VISIBLE;
    }
    public static void setMode(Context context,int mode) {
        if(mode<OFF || mode>COMPANION) throw new IllegalArgumentException("Unknown automatic import mode");
        if(!preferences(context).edit().putInt(MODE_KEY,mode).commit()) throw new IllegalStateException("Could not save automatic import mode");
    }
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE); }
}
