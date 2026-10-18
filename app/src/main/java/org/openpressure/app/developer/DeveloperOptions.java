package org.openpressure.app.developer;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;
import java.util.Objects;
import org.openpressure.protocol.WriteAspectIndex.Aspect;

/**
 * Locally stored developer options: whether the hidden menu is unlocked and which
 * untested write aspects the user explicitly allowed. Storage only; it never talks
 * to a monitor. Only an explicit, aspect-specific request may consult
 * {@link #allowsUntested}; pairing, connecting and imports never do.
 */
public final class DeveloperOptions {
    public static final String PREFERENCES="developer";
    public static final int TAP_TARGET=5;
    public static final long TAP_WINDOW_MS=3000;
    private static final String UNLOCKED_KEY="unlocked", ALLOW_UNTESTED_PREFIX="allow_untested_";
    private DeveloperOptions() {}

    public static boolean isUnlocked(Context context) { return preferences(context).getBoolean(UNLOCKED_KEY,false); }

    /** Locking and unlocking both clear every untested-aspect override in the same commit, so each unlock starts safe. */
    public static void setUnlocked(Context context,boolean unlocked) {
        SharedPreferences.Editor editor=preferences(context).edit().putBoolean(UNLOCKED_KEY,unlocked);
        for(Aspect aspect:Aspect.values()) editor.remove(key(aspect));
        if(!editor.commit()) throw new IllegalStateException("Could not save developer options");
    }

    /** An override only counts while the developer options remain unlocked. */
    public static boolean allowsUntested(Context context,Aspect aspect) {
        SharedPreferences preferences=preferences(context);
        String key=key(aspect);
        return preferences.getBoolean(UNLOCKED_KEY,false) && preferences.getBoolean(key,false);
    }

    public static void setAllowUntested(Context context,Aspect aspect,boolean allowed) {
        String key=key(aspect);
        if(!isUnlocked(context)) throw new IllegalStateException("Developer options are locked");
        if(!preferences(context).edit().putBoolean(key,allowed).commit()) throw new IllegalStateException("Could not save developer options");
    }

    private static String key(Aspect aspect) { return ALLOW_UNTESTED_PREFIX+Objects.requireNonNull(aspect,"aspect").name().toLowerCase(Locale.ROOT); }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE); }

    /** Counts consecutive taps; a gap longer than {@link #TAP_WINDOW_MS} restarts it. */
    public static final class TapCounter {
        private int taps;
        private long lastTapMs;

        /** Returns the taps still needed; 0 means unlocked, after which the count restarts. */
        public int tap(long nowMs) {
            if(taps==0 || nowMs<lastTapMs || nowMs-lastTapMs>TAP_WINDOW_MS) taps=0;
            taps++;
            lastTapMs=nowMs;
            int remaining=TAP_TARGET-taps;
            if(remaining<=0) { taps=0; return 0; }
            return remaining;
        }
    }
}
