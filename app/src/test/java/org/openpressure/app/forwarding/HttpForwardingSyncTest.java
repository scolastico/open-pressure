package org.openpressure.app.forwarding;

import android.app.Application;
import android.content.Context;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.*;
import org.openpressure.protocol.BloodPressure;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real HTTP to a loopback-only server, real SQLite, no patient or device data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class HttpForwardingSyncTest {
    private Context context;
    private ReadingStore store;
    private LoopbackServer server;
    private String endpoint;
    private int response=204;
    private Runnable afterRequest;
    private final List<String> bodies=new ArrayList<>(),auth=new ArrayList<>(),keys=new ArrayList<>(),contentTypes=new ArrayList<>(),methods=new ArrayList<>();
    private final AtomicInteger redirects=new AtomicInteger();
    private final byte[] packet={0,120,0,80,0,93,0};
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication(); store=new ReadingStore(context); ExperimentalConsent.accept(context);
        server=new LoopbackServer();
        server.handle("/measurements",exchange -> {
            bodies.add(new String(exchange.body().readAllBytes(),StandardCharsets.UTF_8));
            auth.add(exchange.header("Authorization")); keys.add(exchange.header("Idempotency-Key"));
            contentTypes.add(exchange.header("Content-Type")); methods.add(exchange.method());
            if(response==302) exchange.responseHeader("Location","/redirect-target");
            if(afterRequest!=null) afterRequest.run();
            exchange.respond(response);
        });
        server.handle("/redirect-target",exchange -> { redirects.incrementAndGet(); exchange.respond(204); });
        server.start(); endpoint="http://127.0.0.1:"+server.port()+"/measurements";
    }
    @After public void close() { server.stop(); store.close(); }
    private boolean add() { return store.add("synthetic-cuff","Synthetic",BloodPressure.parse(packet),packet,"vendor-history",1); }
    private boolean export() { return new HttpForwardingSync(context,store).exportPending(); }
    // Android's javac bootclasspath excludes JDK HTTP server types. Reflection keeps the
    // fixture on the real JVM server without adding server libraries to the Android app.
    private interface Handler { void handle(Exchange exchange) throws Exception; }
    private static final class LoopbackServer {
        private final Class<?> type=Class.forName("com.sun.net.httpserver.HttpServer");
        private final Object server=type.getMethod("create",InetSocketAddress.class,int.class).invoke(null,new InetSocketAddress("127.0.0.1",0),0);
        LoopbackServer() throws Exception { }
        void handle(String path,Handler handler) throws Exception {
            Class<?> handlerType=Class.forName("com.sun.net.httpserver.HttpHandler");
            Object proxy=Proxy.newProxyInstance(handlerType.getClassLoader(),new Class<?>[]{handlerType},(instance,method,args) -> {
                if(method.getName().equals("handle")) { handler.handle(new Exchange(args[0])); return null; }
                if(method.getName().equals("toString")) return "Synthetic loopback handler";
                if(method.getName().equals("hashCode")) return System.identityHashCode(instance);
                if(method.getName().equals("equals")) return instance==args[0];
                return null;
            });
            type.getMethod("createContext",String.class,handlerType).invoke(server,path,proxy);
        }
        void start() throws Exception { type.getMethod("start").invoke(server); }
        int port() throws Exception { return ((InetSocketAddress)type.getMethod("getAddress").invoke(server)).getPort(); }
        void stop() { try { type.getMethod("stop",int.class).invoke(server,0); } catch(Exception failure) { throw new AssertionError(failure); } }
    }
    private static final class Exchange {
        private final Object exchange;
        private final Class<?> type=Class.forName("com.sun.net.httpserver.HttpExchange");
        Exchange(Object exchange) throws Exception { this.exchange=exchange; }
        InputStream body() throws Exception { return (InputStream)type.getMethod("getRequestBody").invoke(exchange); }
        String method() throws Exception { return (String)type.getMethod("getRequestMethod").invoke(exchange); }
        String header(String name) throws Exception {
            Object headers=type.getMethod("getRequestHeaders").invoke(exchange);
            return (String)Class.forName("com.sun.net.httpserver.Headers").getMethod("getFirst",String.class).invoke(headers,name);
        }
        @SuppressWarnings("unchecked") void responseHeader(String key,String value) throws Exception {
            ((Map<String,List<String>>)type.getMethod("getResponseHeaders").invoke(exchange)).put(key,Collections.singletonList(value));
        }
        void respond(int code) throws Exception { type.getMethod("sendResponseHeaders",int.class,long.class).invoke(exchange,code,-1L); type.getMethod("close").invoke(exchange); }
    }
    @Test public void postsVersionedJsonWithOpaqueIdentityAndExactAuthorization() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"Bearer synthetic-only",false); assertTrue(add());
        assertTrue(export()); assertEquals(1,bodies.size()); assertEquals(0,store.httpOutbox().count(endpoint));
        assertEquals("Bearer synthetic-only",auth.get(0)); assertEquals("application/json",contentTypes.get(0)); assertEquals("POST",methods.get(0));
        JSONObject json=new JSONObject(bodies.get(0));
        assertEquals(14,json.length()); assertEquals(1,json.getInt("schema_version")); assertEquals(keys.get(0),json.getString("id"));
        assertEquals("e796429a70145ef3c96ca7365a3c427f8b84fbbefe205d183f0d1620ac55b5c4",json.getString("id"));
        assertEquals("00c7e35c54adfbf8b475bdda5dff40b36e9e70078d93de2e4c2f1e777851f932",json.getString("device_id"));
        assertEquals("vendor-history",json.getString("source")); assertEquals(1,json.getInt("user")); assertTrue(json.isNull("measured_at"));
        assertTrue(json.getString("received_at").endsWith("Z")); assertEquals(120,json.getInt("systolic")); assertEquals(80,json.getInt("diastolic"));
        assertEquals(93,json.getInt("mean_arterial_pressure")); assertEquals("mmHg",json.getString("unit")); assertTrue(json.isNull("pulse")); assertTrue(json.isNull("status")); assertTrue(json.isNull("atrial_fibrillation"));
        assertFalse(bodies.get(0).contains("synthetic-cuff")); assertFalse(bodies.get(0).contains("Bearer"));
        assertFalse(add()); assertEquals(0,store.httpOutbox().count(endpoint));
        HttpForwardingSettings.save(context,endpoint,"",true);
        assertTrue(add()); assertEquals(1,store.historyCount(0)); assertEquals(0,store.httpOutbox().count(endpoint));
    }
    @Test public void datedKpaMeasurementPreservesUnitsNullableMapPulseAndStatus() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false);
        byte[] dated={31,(byte)0xa2,(byte)0xf0,108,(byte)0xf0,(byte)0xff,7,(byte)0xea,7,1,2,3,4,5,65,0,2,4,0};
        assertTrue(store.add("synthetic-cuff","Synthetic",BloodPressure.parse(dated),dated,"sig",null));
        assertTrue(export()); JSONObject json=new JSONObject(bodies.get(0));
        assertEquals("2026-01-02T03:04:05",json.getString("measured_at"));
        assertEquals(16.2,json.getDouble("systolic"),0.0001); assertEquals(10.8,json.getDouble("diastolic"),0.0001);
        assertEquals("kPa",json.getString("unit")); assertTrue(json.isNull("mean_arterial_pressure"));
        assertEquals(65,json.getInt("pulse")); assertEquals(2,json.getInt("user")); assertEquals(4,json.getInt("status"));
    }
    @Test public void errorRetainsExactBodyAndIdentityAcrossRestartAndRetry() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"old-secret",false); assertTrue(add()); response=500;
        assertFalse(export()); assertTrue(HttpForwardingSync.lastAttemptFailed(context)); assertEquals(1,store.httpOutbox().count(endpoint)); assertFalse(add());
        store.close(); store=new ReadingStore(context);
        HttpForwardingSettings.save(context,endpoint,"updated-secret",false); response=201;
        assertTrue(export()); assertFalse(HttpForwardingSync.lastAttemptFailed(context)); assertEquals(bodies.get(0),bodies.get(1)); assertEquals(keys.get(0),keys.get(1)); assertEquals("updated-secret",auth.get(1));
        assertEquals(0,store.httpOutbox().count(endpoint)); assertFalse(add());
    }
    @Test public void failureStatusBelongsToTheDestinationThatWasActuallyAttempted() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(add()); response=500;
        assertFalse(export()); assertTrue(HttpForwardingSync.lastAttemptFailed(context));
        HttpForwardingSettings.save(context,endpoint+"/other","",false);
        assertFalse(HttpForwardingSync.lastAttemptFailed(context));
        HttpForwardingSettings.save(context,endpoint,"",false);
        assertTrue(HttpForwardingSync.lastAttemptFailed(context));
        HttpForwardingSettings.save(context,"","",true);
        assertFalse(HttpForwardingSync.lastAttemptFailed(context));
    }
    @Test public void redirectsNeverReceiveHealthDataOrAuthorization() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"synthetic-secret",false); assertTrue(add()); response=302;
        assertFalse(export()); assertEquals(0,redirects.get()); assertEquals(1,store.httpOutbox().count(endpoint));
    }
    @Test public void changingDestinationPausesOriginalQueueUntilSelectedAgain() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(add());
        HttpForwardingSettings.save(context,endpoint+"/other","",false); assertTrue(export()); assertEquals(0,bodies.size()); assertEquals(1,store.httpOutbox().count(endpoint));
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(export()); assertEquals(1,bodies.size()); assertNull(auth.get(0));
    }
    @Test public void disabledForwardingOrMissingExperimentalConsentNeverSends() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(add());
        HttpForwardingSettings.save(context,"","",false); assertTrue(export()); assertTrue(HttpForwardingSettings.get(context).keepLocal);
        HttpForwardingSettings.save(context,endpoint,"",false); context.getSharedPreferences("experimental_consent",0).edit().clear().commit();
        assertTrue(export()); assertEquals(0,bodies.size()); assertEquals(1,store.httpOutbox().count(endpoint));
    }
    @Test public void deliveredReceiptsContainOnlyHashesAndAcknowledgeRollsBackOnFailure() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(add());
        HttpOutbox.Entry entry=store.httpOutbox().pending(endpoint,10).get(0);
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON http_outbox BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try { store.httpOutbox().acknowledge(entry); fail("Must propagate storage failure"); } catch(android.database.sqlite.SQLiteException expected) { }
        assertEquals(1,store.httpOutbox().count(endpoint));
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM http_receipts",null)) { c.moveToFirst(); assertEquals(0,c.getInt(0)); }
        store.getWritableDatabase().execSQL("DROP TRIGGER reject_delete"); assertTrue(export());
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT destination,event_key FROM http_receipts",null)) { assertTrue(c.moveToFirst()); assertTrue(c.getString(0).matches("[0-9a-f]{64}")); assertTrue(c.getString(1).matches("[0-9a-f]{64}")); }
    }
    @Test public void interruptedWorkerLeavesPayloadPendingWithoutStartingHttp() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(add());
        Thread.currentThread().interrupt();
        try { assertFalse(export()); assertEquals(0,bodies.size()); }
        finally { Thread.interrupted(); }
        assertEquals(1,store.httpOutbox().count(endpoint));
    }
    @Test public void boundedBatchesRetainRemainingPayloadAndRequestRecovery() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false);
        for(int i=0;i<21;i++) store.addManual(120,80,null,1,java.time.LocalDateTime.of(2026,1,2,3,4),"");
        assertFalse(export()); assertEquals(20,bodies.size()); assertEquals(1,store.httpOutbox().count(endpoint));
        assertFalse(HttpForwardingSync.lastAttemptFailed(context));
        assertTrue(export()); assertEquals(21,bodies.size()); assertEquals(0,store.httpOutbox().count(endpoint));
    }
    @Test public void changedSettingsStopTheNextRequestInAnExistingBatch() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"first-secret",false);
        for(int i=0;i<2;i++) store.addManual(120,80,null,1,java.time.LocalDateTime.of(2026,1,2,3,4),"");
        // Change the generation while the first request is in flight, before its response.
        afterRequest=() -> HttpForwardingSettings.save(context,endpoint,"updated-secret",false);
        assertTrue(export()); assertEquals(1,bodies.size()); assertEquals(1,store.httpOutbox().count(endpoint));
        afterRequest=null; assertTrue(export()); assertEquals(2,bodies.size()); assertEquals("updated-secret",auth.get(1));
    }
    @Test public void undatedLiveMeasurementsRemainSeparateEvents() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false);
        for(int i=0;i<2;i++) assertTrue(store.add("synthetic-cuff","Synthetic",BloodPressure.parse(packet),packet,"sig-live",1));
        assertEquals(2,store.httpOutbox().count(endpoint)); assertTrue(export()); assertEquals(2,bodies.size()); assertNotEquals(keys.get(0),keys.get(1));
    }
}
