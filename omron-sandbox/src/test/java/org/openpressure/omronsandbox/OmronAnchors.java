package org.openpressure.omronsandbox;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place that names Omron entry points. Omron's release builds rename most members to single letters,
 * so each anchor is bound by traits that survive renaming: kept class and interface names, exact parameter and
 * return types (compared by name), and enum constant names. A rule must match exactly one member; a missing or
 * ambiguous match is reported by anchor name instead of failing later inside a run.
 *
 * <p>Anchors and rules:
 * <ul>
 * <li>{@code manager}: class {@code ogsc.OGSCManager}; {@code manager.init}: static {@code void (Context, OGSCManagerCallback)};
 * {@code manager.scan}: static {@code ErrorInfo (int, int, int, int, OGSCScanCallback)}: category, model, scan mode, timeout seconds.</li>
 * <li>{@code device}: interface {@code ogsc.OGSCDevice}; {@code device.setDefinition}: {@code ErrorInfo (Bundle)};
 * {@code device.connect}: {@code ErrorInfo (int, byte[], int, OGSCOnConnectStateChangeCallback)}: mode, key, timeout seconds;
 * {@code device.readSettings}: {@code ErrorInfo (ArrayList, OGSCOnGetDeviceSettingCallback)};
 * {@code device.productIndex}: {@code ErrorInfo (OGSCOnGetProductIndexCallback)};
 * {@code device.vitalData}: {@code ErrorInfo (int, OGSCResume, OGSCOnGetVitalDataResumeCallback)};
 * {@code device.disconnect}: the only no-argument method returning {@code ErrorInfo}.</li>
 * <li>{@code resume}: class {@code ogsc.OGSCResume} with an {@code (int user)} constructor.</li>
 * <li>{@code connectionMode.*}, {@code scanMode.*}: {@code getMode()} of the connectivity library enums
 * {@code OGSCConnectionMode} and {@code OGSCScanMode}.</li>
 * <li>{@code pairing.natives}: class {@code ohq.OHQCustomPairing} declaring the native methods bridged by {@link ShadowOmsecNatives}.</li>
 * </ul>
 */
public final class OmronAnchors {
    static final String LIB="jp.co.omron.healthcare.communicationlibrary";
    private static final String OGSC=LIB+".ogsc.",CALLBACK=OGSC+"interfaces.",ERROR=LIB+".utility.ErrorInfo",
        CONTEXT="android.content.Context",BUNDLE="android.os.Bundle",ENUMS="com.omronhealthcare.connectivitylibrary.enums.";

    private final ClassLoader loader;
    private final Map<String,Object> bound=new LinkedHashMap<>();
    private final Map<String,String> rules=new LinkedHashMap<>();

    private OmronAnchors(ClassLoader loader) { this.loader=loader; }

    public static OmronAnchors resolve(ClassLoader loader) {
        OmronAnchors a=new OmronAnchors(loader);
        a.type("manager",OGSC+"OGSCManager");
        a.method("manager.init","manager",true,"void",CONTEXT,CALLBACK+"OGSCManagerCallback");
        a.method("manager.scan","manager",true,ERROR,"int","int","int","int",CALLBACK+"OGSCScanCallback");
        a.type("device",OGSC+"OGSCDevice");
        a.method("device.setDefinition","device",false,ERROR,BUNDLE);
        a.method("device.connect","device",false,ERROR,"int","[B","int",CALLBACK+"OGSCOnConnectStateChangeCallback");
        a.method("device.readSettings","device",false,ERROR,"java.util.ArrayList",CALLBACK+"OGSCOnGetDeviceSettingCallback");
        a.method("device.productIndex","device",false,ERROR,CALLBACK+"OGSCOnGetProductIndexCallback");
        a.method("device.vitalData","device",false,ERROR,"int",OGSC+"OGSCResume",CALLBACK+"OGSCOnGetVitalDataResumeCallback");
        a.method("device.disconnect","device",false,ERROR);
        a.type("resume",OGSC+"OGSCResume");
        a.mode("connectionMode.registration",ENUMS+"OGSCConnectionMode","REGISTRATION");
        a.mode("connectionMode.authentication",ENUMS+"OGSCConnectionMode","AUTHENTICATION");
        a.mode("scanMode.registration",ENUMS+"OGSCScanMode","REGISTRATION");
        a.mode("scanMode.dataTransfer",ENUMS+"OGSCScanMode","DATA_TRANSFER");
        a.bind("pairing.natives","class "+LIB+".ohq.OHQCustomPairing with native methods",ignored -> {
            Class<?> c=a.load(LIB+".ohq.OHQCustomPairing");
            List<Method> natives=new ArrayList<>();
            for(Method m:c.getDeclaredMethods()) if(Modifier.isNative(m.getModifiers())) natives.add(m);
            if(natives.isEmpty()) throw new IllegalStateException("no native methods");
            return natives;
        });
        return a;
    }

    public Class<?> type(String anchor) { return (Class<?>)get(anchor); }
    public Method method(String anchor) { return (Method)get(anchor); }
    public int constant(String anchor) { return (Integer)get(anchor); }
    @SuppressWarnings("unchecked") public List<Method> natives() { return (List<Method>)get("pairing.natives"); }
    public boolean complete() { for(Object value:bound.values()) if(value==null) return false; return true; }
    /** Implements a named Omron callback interface (simple name under {@code ogsc.interfaces}). */
    public Object proxy(String callbackInterface,InvocationHandler handler) {
        Class<?> type=load(CALLBACK+callbackInterface);
        return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args) -> {
            if(method.getDeclaringClass()==Object.class) {
                switch(method.getName()) {
                    case "toString": return "sandbox "+callbackInterface;
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return proxy==args[0];
                }
            }
            return handler.invoke(proxy,method,args);
        });
    }
    public Class<?> load(String name) {
        try { return Class.forName(name,false,loader); }
        catch(ClassNotFoundException missing) { throw new IllegalStateException("class "+name+" not found"); }
    }
    public String report() {
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,Object> e:bound.entrySet()) {
            if(e.getValue()==null) out.append("MISSING ").append(e.getKey()).append(": ").append(rules.get(e.getKey())).append('\n');
            else out.append("ok ").append(e.getKey()).append(" -> ").append(describe(e.getValue())).append('\n');
        }
        return out.toString();
    }

    private Object get(String anchor) {
        Object value=bound.get(anchor);
        if(value==null) throw new IllegalStateException("Omron anchor "+anchor+" not resolved: "+rules.get(anchor)+"\n"+report());
        return value;
    }
    private void type(String anchor,String name) { bind(anchor,"class "+name,ignored -> load(name)); }
    private void method(String anchor,String owner,boolean isStatic,String returns,String... params) {
        String rule=(isStatic?"static ":"")+returns+" ("+String.join(", ",params)+") in "+owner;
        bind(anchor,rule,ignored -> {
            Class<?> type=type(owner);
            List<Method> matches=new ArrayList<>();
            for(Method m:type.getDeclaredMethods()) {
                // Robolectric instrumentation adds renamed copies of each original method.
                if(m.isSynthetic() || m.getName().contains("$")) continue;
                if(Modifier.isStatic(m.getModifiers())!=isStatic || !m.getReturnType().getName().equals(returns)) continue;
                String[] actual=Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(String[]::new);
                if(Arrays.equals(actual,params)) matches.add(m);
            }
            if(matches.size()!=1) throw new IllegalStateException(matches.size()+" matches");
            return matches.get(0);
        });
    }
    private void mode(String anchor,String enumName,String constant) {
        bind(anchor,enumName+"."+constant+".getMode()",ignored -> {
            Class<?> type=load(enumName);
            for(Object value:type.getEnumConstants()) if(((Enum<?>)value).name().equals(constant)) return (Integer)type.getMethod("getMode").invoke(value);
            throw new IllegalStateException("constant missing");
        });
    }
    private interface Rule { Object bind(Void ignored) throws Exception; }
    private void bind(String anchor,String rule,Rule resolver) {
        rules.put(anchor,rule);
        Object value;
        try { value=resolver.bind(null); }
        catch(Throwable unresolved) {
            value=null;
            Throwable cause=unresolved instanceof java.lang.reflect.InvocationTargetException?unresolved.getCause():unresolved;
            rules.put(anchor,rule+" ("+cause+")");
        }
        bound.put(anchor,value);
    }
    private static String describe(Object value) {
        if(value instanceof Method) { Method m=(Method)value; return m.getDeclaringClass().getName()+"."+m.getName(); }
        if(value instanceof Class) return ((Class<?>)value).getName();
        if(value instanceof List) return ((List<?>)value).size()+" native methods";
        return String.valueOf(value);
    }
}
