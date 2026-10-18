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
 * {@link #allowsChosenClockTime} permits only choosing the target time of an explicitly
 * confirmed Sync clock; it never permits a write that the write-aspect index does not permit.
 */
public final class DeveloperOptions {
    public static final String PREFERENCES="developer";
    public static final int TAP_TARGET=5;
    public static final long TAP_WINDOW_MS=3000;
    private static final String UNLOCKED_KEY="unlocked", ALLOW_UNTESTED_PREFIX="allow_untested_", EXPERIMENTAL_PREFIX="experimental_";
    /**
     * Unstable, hardware-untested paths that already exist in code. A switch only selects such a path for an explicit,
     * user-started session; it never authorizes a command the outgoing-write policy does not allow.
     */
    public enum Experiment { FORCE_X7_ENCRYPTED }
    private DeveloperOptions() {}

    public static boolean isUnlocked(Context context) { return preferences(context).getBoolean(UNLOCKED_KEY,false); }

    /** Locking and unlocking both clear every untested-aspect override and experiment in the same commit, so each unlock starts safe. */
    public static void setUnlocked(Context context,boolean unlocked) {
        SharedPreferences.Editor editor=preferences(context).edit().putBoolean(UNLOCKED_KEY,unlocked);
        for(Aspect aspect:Aspect.values()) editor.remove(key(aspect));
        for(Experiment experiment:Experiment.values()) editor.remove(key(experiment));
        if(!editor.commit()) throw new IllegalStateException("Could not save developer options");
    }

    /** An override only counts while the developer options remain unlocked. */
    public static boolean allowsUntested(Context context,Aspect aspect) {
        SharedPreferences preferences=preferences(context);
        String key=key(aspect);
        return preferences.getBoolean(UNLOCKED_KEY,false) && preferences.getBoolean(key,false);
    }

    /**
     * Whether an explicitly confirmed Sync clock may write a developer-chosen time instead of the phone time.
     * Unlocked developer options suffice; it never permits a write the write-aspect index does not permit.
     */
    public static boolean allowsChosenClockTime(Context context) { return isUnlocked(context); }

    public static void setAllowUntested(Context context,Aspect aspect,boolean allowed) {
        String key=key(aspect);
        if(!isUnlocked(context)) throw new IllegalStateException("Developer options are locked");
        if(!preferences(context).edit().putBoolean(key,allowed).commit()) throw new IllegalStateException("Could not save developer options");
    }

    /** An experimental path only counts while the developer options remain unlocked. */
    public static boolean allowsExperimental(Context context,Experiment experiment) {
        SharedPreferences preferences=preferences(context);
        String key=key(experiment);
        return preferences.getBoolean(UNLOCKED_KEY,false) && preferences.getBoolean(key,false);
    }

    public static void setAllowExperimental(Context context,Experiment experiment,boolean allowed) {
        String key=key(experiment);
        if(!isUnlocked(context)) throw new IllegalStateException("Developer options are locked");
        if(!preferences(context).edit().putBoolean(key,allowed).commit()) throw new IllegalStateException("Could not save developer options");
    }

    /** The stored key, for example {@code experimental_force_x7_encrypted}; also the name the device report lists. */
    public static String key(Experiment experiment) { return EXPERIMENTAL_PREFIX+name(experiment); }
    /** The lower-case name of an experiment as the device report lists it. */
    public static String name(Experiment experiment) { return Objects.requireNonNull(experiment,"experiment").name().toLowerCase(Locale.ROOT); }
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
