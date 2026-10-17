package org.openpressure.app.updates;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class UpdateCheckTest {
    private static final String PAGE="https://github.com/scolastico/open-pressure/releases/tag/v1.2.0";
    private Context context;
    private String originalApi;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().clear().commit();
        originalApi=UpdateCheck.releasesApi;
    }
    @After public void restore() { UpdateCheck.releasesApi=originalApi; }

    @Test public void versionsFollowSemverPrecedence() {
        assertTrue(UpdateCheck.compare("1.2.0","1.1.9")>0);
        assertTrue(UpdateCheck.compare("1.10.0","1.9.0")>0);
        assertTrue(UpdateCheck.compare("v2.0.0","1.99.99")>0);
        assertEquals(0,UpdateCheck.compare("1.1.0","v1.1.0"));
        assertTrue(UpdateCheck.compare("1.2.0","1.2.0-rc.2")>0);
        assertTrue(UpdateCheck.compare("1.2.0-rc.10","1.2.0-rc.2")>0);
        assertEquals("unparsable versions are never newer",0,UpdateCheck.compare("latest","1.0.0"));
    }
    @Test public void onlyPublishedReleasesOfThisRepositoryAreAccepted() throws Exception {
        UpdateCheck.Release release=UpdateCheck.parse(json("v1.2.0",PAGE,false,false));
        assertEquals("1.2.0",release.version); assertEquals(PAGE,release.url);
        assertNull(UpdateCheck.parse(json("v1.2.0",PAGE,true,false)));
        assertNull(UpdateCheck.parse(json("v1.2.0-rc.1",PAGE,false,true)));
        assertNull("foreign download pages are ignored",UpdateCheck.parse(json("v1.2.0","https://example.invalid/releases/v1.2.0",false,false)));
        assertNull(UpdateCheck.parse(json("nightly",PAGE,false,false)));
        assertNull("missing flags are treated as unpublished",UpdateCheck.parse("{\"tag_name\":\"v1.2.0\",\"html_url\":\""+PAGE+"\"}"));
    }
    @Test public void remindLaterAndSkipSuppressOnlyTheirScope() {
        store("1.2.0");
        assertEquals("1.2.0",UpdateCheck.available(context,"1.1.0",1000).version);
        assertNull("current or newer installs are not prompted",UpdateCheck.available(context,"1.2.0",1000));
        UpdateCheck.remindLater(context,1000);
        assertNull(UpdateCheck.available(context,"1.1.0",1000+UpdateCheck.REMIND_DELAY-1));
        assertNotNull(UpdateCheck.available(context,"1.1.0",1000+UpdateCheck.REMIND_DELAY));
        UpdateCheck.skip(context,"1.2.0");
        assertNull(UpdateCheck.available(context,"1.1.0",Long.MAX_VALUE));
        store("1.3.0");
        assertEquals("a skipped version does not hide the next one","1.3.0",UpdateCheck.available(context,"1.1.0",Long.MAX_VALUE).version);
        UpdateCheck.setEnabled(context,false);
        assertNull(UpdateCheck.available(context,"1.1.0",Long.MAX_VALUE));
    }
    @Test public void checksAreDailyEnabledByDefaultAndStopWhenTurnedOff() {
        assertTrue(UpdateCheck.enabled(context)); assertTrue(UpdateCheck.due(context,10));
        context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putLong("checked_at",10).commit();
        assertFalse(UpdateCheck.due(context,10+UpdateCheck.CHECK_INTERVAL-1));
        assertTrue(UpdateCheck.due(context,10+UpdateCheck.CHECK_INTERVAL));
        UpdateCheck.setEnabled(context,false); assertFalse(UpdateCheck.due(context,Long.MAX_VALUE));
        UpdateCheck.setEnabled(context,true); assertTrue("re-enabling checks on the next start",UpdateCheck.due(context,11));
    }
    @Test public void backgroundCheckSendsOnlyAnAnonymousGetAndStoresTheRelease() throws Exception {
        List<String> request=new ArrayList<>();
        try(ServerSocket server=new ServerSocket(0)) {
            UpdateCheck.releasesApi="http://127.0.0.1:"+server.getLocalPort()+"/repos/scolastico/open-pressure/releases/latest";
            Thread peer=new Thread(() -> serve(server,request,200,json("v9.0.0",PAGE,false,false)));
            peer.start();
            AtomicBoolean done=new AtomicBoolean();
            UpdateCheck.checkIfDue(context,() -> done.set(true));
            peer.join(10000);
            for(int i=0;i<200 && !done.get();i++) { Thread.sleep(25); shadowOf(Looper.getMainLooper()).idle(); }
            assertTrue(done.get());
        }
        assertTrue(request.get(0).startsWith("GET /repos/scolastico/open-pressure/releases/latest "));
        assertTrue(request.stream().noneMatch(line -> line.toLowerCase().startsWith("authorization") || line.toLowerCase().startsWith("cookie")));
        assertTrue(request.stream().anyMatch(line -> line.startsWith("User-Agent: OpenPressure/")));
        assertEquals("9.0.0",UpdateCheck.available(context,"1.1.0",System.currentTimeMillis()).version);
        assertFalse("a completed check waits a day",UpdateCheck.due(context,System.currentTimeMillis()));
    }
    @Test public void failedCheckKeepsPreviousStateAndWaitsForTheNextInterval() throws Exception {
        store("1.2.0");
        try(ServerSocket server=new ServerSocket(0)) {
            UpdateCheck.releasesApi="http://127.0.0.1:"+server.getLocalPort()+"/latest";
            Thread peer=new Thread(() -> serve(server,new ArrayList<>(),403,"{\"message\":\"rate limited\"}"));
            peer.start();
            AtomicBoolean done=new AtomicBoolean();
            UpdateCheck.checkIfDue(context,() -> done.set(true));
            peer.join(10000);
            for(int i=0;i<200 && !done.get();i++) { Thread.sleep(25); shadowOf(Looper.getMainLooper()).idle(); }
            assertTrue(done.get());
        }
        assertEquals("1.2.0",UpdateCheck.available(context,"1.1.0",System.currentTimeMillis()).version);
        assertFalse(UpdateCheck.due(context,System.currentTimeMillis()));
    }
    private void store(String version) {
        context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putString("latest_version",version).putString("latest_url",PAGE).commit();
    }
    private static String json(String tag,String url,boolean draft,boolean prerelease) {
        return "{\"tag_name\":\""+tag+"\",\"html_url\":\""+url+"\",\"draft\":"+draft+",\"prerelease\":"+prerelease+"}";
    }
    private static void serve(ServerSocket server,List<String> request,int code,String body) {
        try(Socket socket=server.accept()) {
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            for(String line=in.readLine();line!=null && !line.isEmpty();line=in.readLine()) request.add(line);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();
            out.write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes); out.flush();
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
}
