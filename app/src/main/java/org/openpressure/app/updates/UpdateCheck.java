package org.openpressure.app.updates;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import org.openpressure.app.BuildConfig;

/**
 * Occasional check for a newer GitHub release. The request is an anonymous HTTPS GET to the
 * public releases API; it carries no readings, monitor details or identifiers beyond the app version.
 * Installing remains the user's choice through the release page and Android's package installer.
 */
public final class UpdateCheck {
    static String releasesApi="https://api.github.com/repos/scolastico/open-pressure/releases/latest";
    static final String RELEASE_PAGE="https://github.com/scolastico/open-pressure/releases/";
    static final long CHECK_INTERVAL=24L*60*60*1000, REMIND_DELAY=3L*24*60*60*1000;
    private static final int MAX_RESPONSE=256*1024;
    private static final Pattern VERSION=Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?");
    private static final AtomicBoolean running=new AtomicBoolean();

    public static final class Release {
        public final String version,url;
        Release(String version,String url) { this.version=version; this.url=url; }
    }
    private UpdateCheck() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("updates",Context.MODE_PRIVATE); }
    public static boolean enabled(Context context) { return prefs(context).getBoolean("enabled",true); }
    /** Turning checks back on makes the next app start check immediately. */
    public static void setEnabled(Context context,boolean enabled) {
        SharedPreferences.Editor edit=prefs(context).edit().putBoolean("enabled",enabled);
        if(enabled) edit.remove("checked_at");
        edit.apply();
    }
    static boolean due(Context context,long now) {
        SharedPreferences p=prefs(context);
        if(!enabled(context)) return false;
        if(!p.contains("checked_at")) return true;
        long last=p.getLong("checked_at",0);
        return now<last || now-last>=CHECK_INTERVAL;
    }

    /** A known newer release that the user has neither skipped nor postponed. */
    public static Release available(Context context,String current,long now) {
        SharedPreferences p=prefs(context);
        String version=p.getString("latest_version",null),url=p.getString("latest_url",null);
        if(!enabled(context) || version==null || url==null || version.equals(p.getString("skipped",null))) return null;
        if(now<p.getLong("remind_after",0)) return null;
        return compare(version,current)>0?new Release(version,url):null;
    }
    public static void remindLater(Context context,long now) { prefs(context).edit().putLong("remind_after",now+REMIND_DELAY).apply(); }
    public static void skip(Context context,String version) { prefs(context).edit().putString("skipped",version).apply(); }

    /** Runs at most one background check per interval and calls back on the main thread. */
    public static void checkIfDue(Context context,Runnable done) {
        Context app=context.getApplicationContext();
        long now=System.currentTimeMillis();
        if(!due(app,now) || !running.compareAndSet(false,true)) return;
        // Record the attempt first so failures and rate limits wait for the next interval.
        prefs(app).edit().putLong("checked_at",now).apply();
        new Thread(() -> {
            try {
                Release release=parse(fetch());
                if(release!=null) prefs(app).edit().putString("latest_version",release.version).putString("latest_url",release.url).apply();
            } catch(IOException | JSONException | RuntimeException ignored) {
                // Offline, rate-limited or unexpected responses leave the previous state unchanged.
            } finally {
                running.set(false);
                new Handler(Looper.getMainLooper()).post(done);
            }
        },"update-check").start();
    }

    static String fetch() throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(releasesApi).openConnection();
        try {
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
            connection.setRequestProperty("User-Agent","OpenPressure/"+BuildConfig.VERSION_NAME);
            if(connection.getResponseCode()!=200) throw new IOException("Release check returned "+connection.getResponseCode());
            try(InputStream input=connection.getInputStream()) {
                ByteArrayOutputStream body=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(body.size()+count>MAX_RESPONSE) throw new IOException("Release response too large");
                    body.write(buffer,0,count);
                }
                return body.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    /** Published, non-prerelease releases of this repository only; anything else is ignored. */
    static Release parse(String json) throws JSONException {
        JSONObject release=new JSONObject(json);
        if(release.optBoolean("draft",true) || release.optBoolean("prerelease",true)) return null;
        String tag=release.optString("tag_name",""),url=release.optString("html_url","");
        if(!VERSION.matcher(tag).matches() || !url.startsWith(RELEASE_PAGE)) return null;
        return new Release(tag.startsWith("v")?tag.substring(1):tag,url);
    }

    /** SemVer precedence for the version forms this project publishes; unparsable versions never compare newer. */
    static int compare(String a,String b) {
        Matcher left=VERSION.matcher(a==null?"":a),right=VERSION.matcher(b==null?"":b);
        if(!left.matches() || !right.matches()) return 0;
        for(int i=1;i<=3;i++) {
            int order=Long.compare(Long.parseLong(left.group(i)),Long.parseLong(right.group(i)));
            if(order!=0) return order;
        }
        String pa=left.group(4),pb=right.group(4);
        if(pa==null || pb==null) return pa==null?(pb==null?0:1):-1;
        String[] x=pa.split("\\."),y=pb.split("\\.");
        for(int i=0;i<Math.min(x.length,y.length);i++) {
            boolean nx=x[i].matches("\\d+"),ny=y[i].matches("\\d+");
            int order=nx && ny?Long.compare(Long.parseLong(x[i]),Long.parseLong(y[i])):nx?-1:ny?1:x[i].compareTo(y[i]);
            if(order!=0) return order;
        }
        return Integer.compare(x.length,y.length);
    }
}
