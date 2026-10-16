package org.openpressure.app.forwarding;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.net.URISyntaxException;

/** Private opt-in configuration. Never include the authorization value in diagnostics. */
public final class HttpForwardingSettings {
    private HttpForwardingSettings() {}
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("http_forwarding",Context.MODE_PRIVATE);
    }
    public static final class Config {
        public final String endpoint,authorization;
        public final boolean keepLocal;
        public final long generation;
        private Config(String endpoint,String authorization,boolean keepLocal,long generation) {
            this.endpoint=endpoint; this.authorization=authorization;
            this.keepLocal=endpoint.isEmpty() || keepLocal; this.generation=generation;
        }
        public boolean enabled() { return !endpoint.isEmpty(); }
    }
    public static synchronized Config get(Context context) {
        SharedPreferences p=preferences(context);
        return new Config(p.getString("endpoint",""),p.getString("authorization",""),p.getBoolean("keep_local",true),p.getLong("generation",0));
    }
    public static boolean validEndpoint(String endpoint) {
        if(endpoint==null || endpoint.isEmpty()) return false;
        try {
            URI uri=new URI(endpoint);
            if(!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null || uri.getHost().isEmpty() || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || uri.getPort()>65535 || uri.getPort()==0) return false;
            uri.toURL(); return true;
        } catch(URISyntaxException | java.net.MalformedURLException invalid) { return false; }
    }
    public static synchronized void save(Context context,String endpoint,String authorization,boolean keepLocal) {
        if(endpoint==null || authorization==null) throw new IllegalArgumentException("Missing configuration");
        for(int i=0;i<endpoint.length();i++) if(Character.isISOControl(endpoint.charAt(i))) throw new IllegalArgumentException("Invalid destination");
        endpoint=endpoint.trim();
        if(!endpoint.isEmpty() && !validEndpoint(endpoint)) throw new IllegalArgumentException("Invalid destination");
        for(int i=0;i<authorization.length();i++) if(authorization.charAt(i)<32 || authorization.charAt(i)>126) throw new IllegalArgumentException("Invalid authorization value");
        Config old=get(context);
        SharedPreferences p=preferences(context);
        if(!p.edit().putString("endpoint",endpoint).putString("authorization",authorization).putBoolean("keep_local",endpoint.isEmpty() || keepLocal).putLong("generation",old.generation+1).commit()) {
            p.edit().putString("endpoint",old.endpoint).putString("authorization",old.authorization).putBoolean("keep_local",old.keepLocal).putLong("generation",old.generation).commit();
            throw new IllegalStateException("Could not save forwarding settings");
        }
    }
}
