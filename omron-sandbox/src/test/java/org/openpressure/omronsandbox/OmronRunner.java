package org.openpressure.omronsandbox;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.FixtureCursor;
import org.openpressure.protocol.fixture.Outcome;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import static org.robolectric.Shadows.shadowOf;

/**
 * Runs Omron Connect's own communication library against one fixture inside Robolectric, in the order the
 * app's {@code OmronCommLibManager} uses: initialize OGSC, scan for the fixture's advertisement, pass the
 * catalog-selected definition folder, connect in registration or authentication mode with the fixture key
 * (where the app passes its account-derived key), then request vital data per user. Only the Bluetooth peer
 * is replaced; see {@link FixtureGatt}. Must run inside a Robolectric test configured like {@code OmronRunnerTest}.
 */
public final class OmronRunner {
    private static final String ADDRESS="AA:BB:CC:DD:EE:FF";
    /** Virtual time a whole run may wait; longer than any Omron timeout seen, so stalls end in Omron's own error. */
    private static final long MAX_WAIT_MS=10*60*1000;
    private long waited;
    /** Virtual-time step while an Omron action paces itself; its delays are tens of milliseconds. */
    private static final long PACING_STEP_MS=10;
    private final Fixture fixture;
    private final OmronApk apk;
    private final List<String> log=new ArrayList<>();
    private final List<Bundle> vitals=new ArrayList<>();
    private FixtureCursor cursor;
    private FixtureGatt peer;
    private OmronAnchors anchors;
    private Object device;
    private String failure;
    private boolean vitalDone,connected,disconnected;

    private OmronRunner(Fixture fixture,OmronApk apk) { this.fixture=fixture; this.apk=apk; }

    /** Null when Omron's catalog knows the advertised model, otherwise the UNSUPPORTED outcome. */
    static Outcome select(Fixture f,OmronCatalog catalog) {
        int[] ids=OmronCatalog.ids(f.advertisedName);
        if(ids==null) return Outcome.unsupported("advertised name "+f.advertisedName+" carries no Omron model ID");
        if(catalog.folderFor(f.advertisedName)==null)
            return Outcome.unsupported(String.format(Locale.ROOT,"no Omron catalog entry for category 0x%04X model 0x%04X",ids[0],ids[1]));
        return null;
    }

    private static boolean networkBlocked;
    /** Omron's libraries include analytics that post over HTTP; the sandbox must never reach the network. */
    static synchronized void blockNetwork() {
        if(networkBlocked) return;
        // The factory is JVM-wide while this class may be loaded once per Robolectric sandbox.
        try { java.net.URL.setURLStreamHandlerFactory(protocol -> protocol.equals("http") || protocol.equals("https") || protocol.equals("ftp")?new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(java.net.URL url) throws IOException { throw new IOException("network disabled in the Omron sandbox"); }
        }:null); }
        catch(Error alreadySet) {
            try { new java.net.URL("http://sandbox.invalid/").openConnection(); }
            catch(IOException blocked) { networkBlocked=true; return; }
            throw new IllegalStateException("Another URL handler factory is installed; the Omron sandbox cannot block the network",alreadySet);
        }
        networkBlocked=true;
    }

    public static Outcome run(Fixture f,OmronApk apk,Path transcript) throws IOException {
        blockNetwork();
        OmronRunner runner=new OmronRunner(f,apk);
        Outcome outcome;
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        // A crash on one of Omron's threads would otherwise only stall the run.
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> {
            runner.log("uncaught on "+thread.getName()+": "+stack(error));
            if(runner.failure==null) runner.failure="crash on "+thread.getName()+": "+error;
        });
        try { outcome=runner.execute(); }
        catch(Throwable unexpected) { outcome=Outcome.rejected(runner.cursor==null?0:runner.cursor.position(),"sandbox error: "+unexpected); runner.log("sandbox error: "+stack(unexpected)); }
        finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            // Omron's HandlerThreads would otherwise outlive the run and keep its class loader alive.
            for(Looper looper:ShadowLooper.getAllLoopers()) if(background(looper)) looper.quit();
        }
        runner.write(transcript,outcome);
        SandboxBluetoothDevice.peer=null;
        return outcome;
    }

    private Outcome execute() throws Exception {
        Application context=RuntimeEnvironment.getApplication();
        OmronCatalog catalog=new OmronCatalog(new String(Files.readAllBytes(apk.deviceModelsJson),StandardCharsets.UTF_8));
        Outcome unsupported=select(fixture,catalog);
        if(unsupported!=null) return unsupported;
        OmronCatalog.Entry entry=catalog.folderFor(fixture.advertisedName);
        Path folder=extractDefinitions(context).resolve(entry.folder());
        log("catalog: "+fixture.advertisedName+" -> "+entry.identifier+" ("+entry.name+")");
        anchors=OmronAnchors.resolve(OmronRunner.class.getClassLoader());
        if(!anchors.complete()) return Outcome.rejected(0,"Omron anchors unresolved:\n"+anchors.report());
        cursor=new FixtureCursor(fixture,Fixture.Client.OMRON);
        peer=new FixtureGatt(fixture,cursor);
        SandboxBluetoothDevice.peer=peer;
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.BLUETOOTH,Manifest.permission.BLUETOOTH_ADMIN);
        BluetoothAdapter adapter=((BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        shadowOf(adapter).setEnabled(true);
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);

        call("manager.init",null,context,anchors.proxy("OGSCManagerCallback",(p,m,args) -> { log("callback OGSCManagerCallback."+m.getName()+describe(args)); return null; }));
        pump();
        if(failure!=null) return rejected(failure);
        boolean pairing=fixture.flow.equals("pair");
        int[] ids=OmronCatalog.ids(fixture.advertisedName);
        Object scan=anchors.proxy("OGSCScanCallback",(p,m,args) -> {
            log("callback OGSCScanCallback."+m.getName()+describe(args));
            if(m.getName().equals("onScan") && device==null) device=args[0];
            return null;
        });
        if(!ok(call("manager.scan",null,ids[0],ids[1],anchors.constant(pairing?"scanMode.registration":"scanMode.dataTransfer"),30,scan))) return rejected("scan refused");
        pump();
        ScanResult result=scanResult(adapter.getRemoteDevice(ADDRESS));
        java.util.Set<android.bluetooth.le.ScanCallback> scanners=shadowOf(adapter.getBluetoothLeScanner()).getScanCallbacks();
        log("delivering advertisement to "+scanners.size()+" scan callback(s)");
        for(android.bluetooth.le.ScanCallback callback:scanners) callback.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES,result);
        pump();
        if(device==null) return rejected("Omron ignored the advertisement");

        Bundle definition=new Bundle();
        definition.putString("wlpDefinitionFolderPath",folder.toString());
        if(!ok(call("device.setDefinition",device,definition))) return rejected("definition folder refused");
        Object connect=anchors.proxy("OGSCOnConnectStateChangeCallback",(p,m,args) -> {
            log("callback OGSCOnConnectStateChangeCallback."+m.getName()+describe(args));
            if(args!=null && args.length==4 && args[3]!=null && !isSuccess(args[3])) failure="connect: "+args[3];
            // Arguments are (old state, new state, reason, error); observed states: 1 disconnected, 2 connecting, 3 connected, 4 disconnecting.
            if(args!=null && args.length==4 && args[1] instanceof Integer) { connected=(Integer)args[1]==3; if((Integer)args[1]==1) disconnected=true; }
            return null;
        });
        if(!ok(call("device.connect",device,anchors.constant(pairing?"connectionMode.registration":"connectionMode.authentication"),fixture.key.clone(),60,connect))) return rejected("connect refused");
        pump(() -> connected || disconnected || failure!=null || peer.outcome()!=null);
        if(!connected && failure==null && peer.outcome()==null) return rejected("never reported a connection");
        if(failure!=null) return rejected(failure);

        List<Integer> users=new ArrayList<>();
        for(Fixture.Reading r:fixture.readings()) if(!users.contains(r.user)) users.add(r.user);
        if(users.isEmpty()) users.add(1);
        for(int user:users) {
            vitalDone=false;
            Object resume=anchors.type("resume").getConstructor(int.class).newInstance(user);
            Object vital=anchors.proxy("OGSCOnGetVitalDataResumeCallback",(p,m,args) -> {
                log("callback OGSCOnGetVitalDataResumeCallback."+m.getName()+describe(args));
                if(m.getName().equals("onGetVitalData") && args[0] instanceof Bundle) vitals.add((Bundle)args[0]);
                if(m.getName().equals("onGetVitalDataCompleted")) { vitalDone=true; if(args[3]!=null && !isSuccess(args[3])) failure="vital data: "+args[3]; }
                return null;
            });
            if(!ok(call("device.vitalData",device,user+4112,resume,vital))) return rejected("vital data request refused");
            pump(() -> vitalDone || disconnected || failure!=null || peer.outcome()!=null);
            if(failure!=null) return rejected(failure);
        }
        call("device.disconnect",device);
        pump(() -> disconnected || peer.outcome()!=null);
        if(peer.outcome()!=null) return peer.outcome();
        if(!cursor.finished()) return cursor.stalled();
        return cursor.finish(OmronVitals.readings(vitals,log));
    }

    /**
     * Runs the main looper and queued peer events until nothing is left. Omron's HandlerThreads run on their own
     * threads; the frozen Robolectric clock keeps their delayed timeouts from firing, so the pump only waits until
     * each background queue has no due message before delivering the next peer event.
     */
    private void pump() throws InterruptedException { pump(null); }
    /**
     * Without a condition, settles without letting time pass. With one, also advances virtual time to the next
     * scheduled message whenever nothing is runnable, until the condition holds or the time budget is spent.
     */
    private void pump(java.util.function.BooleanSupplier until) throws InterruptedException {
        for(int guard=0;guard<1_000_000;guard++) {
            if(until!=null && until.getAsBoolean()) return;
            Looper main=Looper.getMainLooper();
            boolean progressed=false;
            if(!shadowOf(main).isIdle()) { shadowOf(main).idle(); progressed=true; }
            if(waitForBackground()) progressed=true;
            if(peer!=null && peer.outcome()==null && peer.deliverNext()) progressed=true;
            if(progressed) continue;
            if(until==null || until.getAsBoolean()) return;
            // Nothing is runnable: let virtual time pass until the next scheduled message, as a real wait would.
            long now=android.os.SystemClock.uptimeMillis(),next=Long.MAX_VALUE;
            if(pacingWait()) next=now+PACING_STEP_MS;
            for(Looper looper:ShadowLooper.getAllLoopers()) {
                if(looper!=Looper.getMainLooper() && !looper.getThread().isAlive()) continue;
                long at=shadowOf(looper).getNextScheduledTaskTime().toMillis();
                if(at>now) next=Math.min(next,at);
            }
            if(next==Long.MAX_VALUE || waited+(next-now)>MAX_WAIT_MS) return;
            waited+=next-now;
            if(next-now!=PACING_STEP_MS) log("time +"+(next-now)+" ms");
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(next-now));
            // A pacing wait re-checks the clock only after its real-time Object.wait slice ends.
            if(pacingWait()) Thread.sleep(PACING_STEP_MS);
        }
        throw new IllegalStateException("pump did not settle");
    }
    /** True if any background looper or Omron thread still had work when first checked. */
    private boolean waitForBackground() throws InterruptedException {
        boolean busy=false;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(true) {
            if(quiet()) {
                // Give a just-dequeued message time to start before declaring the threads quiet.
                Thread.sleep(2);
                if(quiet()) return busy;
            }
            busy=true;
            if(System.nanoTime()>deadline) throw new IllegalStateException("Omron background threads did not settle: "+threads());
            Thread.sleep(1);
        }
    }
    /** No live background looper has due work and no Omron or looper thread is running. */
    private static boolean quiet() {
        for(Looper looper:ShadowLooper.getAllLoopers()) if(background(looper) && !shadowOf(looper).isIdle()) return false;
        for(java.util.Map.Entry<Thread,StackTraceElement[]> e:Thread.getAllStackTraces().entrySet()) {
            if(e.getKey()==Thread.currentThread() || e.getKey().getState()!=Thread.State.RUNNABLE) continue;
            for(StackTraceElement frame:e.getValue()) if(frame.getClassName().startsWith("jp.co.omron.") || frame.getClassName().equals("android.os.Looper")) return false;
        }
        return true;
    }
    /** Omron's state machine paces actions with an Object.wait loop timed by the (virtual) nanoTime clock. */
    private static boolean pacingWait() {
        for(java.util.Map.Entry<Thread,StackTraceElement[]> e:Thread.getAllStackTraces().entrySet())
            for(StackTraceElement frame:e.getValue()) if(frame.getClassName().equals(PACING)) return true;
        return false;
    }
    private static final String PACING=OmronAnchors.LIB+".statemachine.Interface.IDoAction$Status";
    private static String threads() {
        StringBuilder out=new StringBuilder();
        for(Thread t:Thread.getAllStackTraces().keySet()) out.append(t.getName()).append('/').append(t.getState()).append(' ');
        return out.toString();
    }

    /** A live background looper; a quit HandlerThread keeps its undelivered messages but never runs them. */
    private static boolean background(Looper looper) {
        return looper!=Looper.getMainLooper() && looper.getThread().isAlive();
    }
    private Object call(String anchor,Object target,Object... args) throws Exception {
        Method method=anchors.method(anchor);
        method.setAccessible(true);
        try {
            Object result=method.invoke(target,args);
            log(anchor+describe(args)+" -> "+result);
            return result;
        } catch(InvocationTargetException thrown) { throw new IllegalStateException(anchor+" threw "+thrown.getCause(),thrown.getCause()); }
    }
    private Outcome rejected(String why) {
        if(peer!=null && peer.outcome()!=null) return peer.outcome();
        return Outcome.rejected(cursor==null?0:cursor.position(),"Omron: "+why+(cursor==null?"":"; "+cursor.stalled().detail));
    }
    /** ErrorInfo success as Omron checks it: the low 32 bits of the code (its only no-argument long getter) are 0. */
    private static boolean ok(Object errorInfo) { return errorInfo==null || isSuccess(errorInfo); }
    private static boolean isSuccess(Object errorInfo) {
        Class<?> type=errorInfo.getClass();
        while(type!=null && !type.getName().equals(OmronAnchors.LIB+".utility.ErrorInfo")) type=type.getSuperclass();
        if(type==null) throw new IllegalStateException("not an Omron ErrorInfo: "+errorInfo.getClass());
        for(Method m:type.getDeclaredMethods())
            if(m.getParameterCount()==0 && m.getReturnType()==long.class && !m.getName().contains("$") && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                try { m.setAccessible(true); return ((Long)m.invoke(errorInfo)&0xffffffffL)==0; }
                catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
        throw new IllegalStateException("ErrorInfo code getter not found");
    }

    private ScanResult scanResult(BluetoothDevice device) throws Exception {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        raw.write(new byte[]{2,0x01,0x06});
        byte[] name=fixture.advertisedName.getBytes(StandardCharsets.US_ASCII);
        raw.write(name.length+1); raw.write(0x09); raw.write(name);
        for(UUID service:fixture.advertisedServices) {
            boolean sig=(service.getLeastSignificantBits()==0x800000805f9b34fbL) && (service.getMostSignificantBits()&0xffff0000ffffffffL)==0x1000L;
            if(sig) { int id=(int)(service.getMostSignificantBits()>>>32)&0xffff; raw.write(new byte[]{3,0x03,(byte)id,(byte)(id>>8)}); }
            else {
                byte[] le=new byte[16];
                long msb=service.getMostSignificantBits(),lsb=service.getLeastSignificantBits();
                for(int i=0;i<8;i++) { le[i]=(byte)(lsb>>>(8*i)); le[8+i]=(byte)(msb>>>(8*i)); }
                raw.write(17); raw.write(0x07); raw.write(le);
            }
        }
        if(fixture.manufacturerData!=null) { raw.write(fixture.manufacturerData.length+1); raw.write(0xff); raw.write(fixture.manufacturerData); }
        Method parse=ScanRecord.class.getDeclaredMethod("parseFromBytes",byte[].class);
        parse.setAccessible(true);
        ScanRecord record=(ScanRecord)parse.invoke(null,(Object)raw.toByteArray());
        log("advertise "+FixtureGatt.hex(raw.toByteArray()));
        return new ScanResult(device,record,-50,0);
    }

    private Path extractDefinitions(Context context) throws IOException {
        Path root=context.getFilesDir().toPath().resolve("omron-sandbox");
        if(Files.isDirectory(root.resolve("device_category"))) return root;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(apk.deviceCategoryZip))) {
            for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                Path target=root.resolve(e.getName()).normalize();
                if(!target.startsWith(root)) throw new IOException("unsafe entry "+e.getName());
                if(e.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                Files.write(target,zip.readAllBytes());
            }
        }
        return root;
    }

    private void write(Path transcript,Outcome outcome) throws IOException {
        StringBuilder out=new StringBuilder("omron ").append(apk.version).append(": ").append(outcome.report()).append("\n\n");
        for(String line:log) out.append(line).append('\n');
        if(peer!=null) { out.append("\n-- link --\n"); for(String line:peer.transcript()) out.append(line).append('\n'); }
        out.append("\n-- Omron log (last 400) --\n");
        List<ShadowLog.LogItem> logs=ShadowLog.getLogs();
        for(ShadowLog.LogItem item:logs.subList(Math.max(0,logs.size()-400),logs.size()))
            out.append(item.type).append(' ').append(item.tag).append(": ").append(item.msg).append(item.throwable==null?"":" "+item.throwable).append('\n');
        Files.createDirectories(transcript.toAbsolutePath().getParent());
        Files.write(transcript,out.toString().getBytes(StandardCharsets.UTF_8));
    }
    void log(String line) { log.add(line); }
    private static String describe(Object[] args) {
        if(args==null) return "()";
        StringBuilder out=new StringBuilder("(");
        for(int i=0;i<args.length;i++) {
            Object a=args[i];
            out.append(i>0?", ":"").append(a instanceof byte[]?FixtureGatt.hex((byte[])a):a instanceof Bundle?bundle((Bundle)a):String.valueOf(a));
        }
        return out.append(')').toString();
    }
    static String bundle(Bundle b) {
        StringBuilder out=new StringBuilder("{");
        for(String key:new java.util.TreeSet<>(b.keySet())) {
            Object v=b.get(key);
            out.append(key).append('=').append(v instanceof byte[]?FixtureGatt.hex((byte[])v):v instanceof Bundle?bundle((Bundle)v):v instanceof Object[]?Arrays.toString((Object[])v):String.valueOf(v)).append(", ");
        }
        return out.append('}').toString();
    }
    private static String stack(Throwable t) {
        java.io.StringWriter w=new java.io.StringWriter(); t.printStackTrace(new java.io.PrintWriter(w)); return w.toString();
    }
}
