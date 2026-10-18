package org.openpressure.omronsandbox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Obfuscated Omron entry points are bound only through stable traits, and a missing one is named. */
public class OmronAnchorsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String P="jp.co.omron.healthcare.communicationlibrary";

    @Test public void missingAnchorIsReportedByName() throws Exception {
        Path src=temp.newFolder("src").toPath(),out=temp.newFolder("out").toPath();
        source(src,"android.content.Context","package android.content; public class Context {}");
        source(src,"android.os.Bundle","package android.os; public class Bundle {}");
        source(src,P+".utility.ErrorInfo","package "+P+".utility; public class ErrorInfo {}");
        for(String callback:new String[]{"OGSCManagerCallback","OGSCScanCallback","OGSCOnConnectStateChangeCallback"})
            source(src,P+".ogsc.interfaces."+callback,"package "+P+".ogsc.interfaces; public interface "+callback+" {}");
        source(src,P+".ogsc.OGSCManager","package "+P+".ogsc; import "+P+".ogsc.interfaces.*; import "+P+".utility.ErrorInfo;"+
            " public class OGSCManager { public static void f(android.content.Context c,OGSCManagerCallback cb) {}"+
            " public static ErrorInfo j(int a,int b,int c,int d,OGSCScanCallback cb) { return null; } }");
        // The device stub lacks the connect signature (int, byte[], int, callback).
        source(src,P+".ogsc.OGSCDevice","package "+P+".ogsc; import "+P+".utility.ErrorInfo;"+
            " public interface OGSCDevice { ErrorInfo b(android.os.Bundle b); ErrorInfo e(); }");
        compile(src,out);
        try(URLClassLoader loader=new URLClassLoader(new URL[]{out.toUri().toURL()},null)) {
            OmronAnchors anchors=OmronAnchors.resolve(loader);
            String report=anchors.report();
            assertFalse(anchors.complete());
            assertTrue(report,report.contains("ok manager.init -> "+P+".ogsc.OGSCManager.f"));
            assertTrue(report,report.contains("ok device.disconnect -> "+P+".ogsc.OGSCDevice.e"));
            assertTrue(report,report.contains("MISSING device.connect"));
            try { anchors.method("device.connect"); fail("missing anchor returned a member"); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("device.connect")); }
        }
    }

    @Test public void resolvesRealApk() throws Exception {
        OmronApk.assumePresent();
        OmronAnchors anchors=OmronAnchors.resolve(OmronAnchorsTest.class.getClassLoader());
        System.out.println(anchors.report());
        Path out=java.nio.file.Paths.get("build","omron-sandbox","resolve.txt");
        Files.createDirectories(out.getParent());
        Files.write(out,("Omron "+OmronApk.locate().version+" anchors\n"+anchors.report()).getBytes(StandardCharsets.UTF_8));
        assertTrue(anchors.report(),anchors.complete());
        assertEquals(1,anchors.constant("connectionMode.registration"));
        assertEquals(0,anchors.constant("connectionMode.authentication"));
    }

    private static void source(Path root,String name,String code) throws Exception {
        Path file=root.resolve(name.replace('.','/')+".java");
        Files.createDirectories(file.getParent());
        Files.write(file,code.getBytes(StandardCharsets.UTF_8));
    }
    private static void compile(Path src,Path out) throws Exception {
        // javax.tools is absent from the Android compile class path; the test JVM is a full JDK.
        Object compiler=Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null);
        List<String> args=new ArrayList<>(List.of("-d",out.toString()));
        try(java.util.stream.Stream<Path> files=Files.walk(src)) { files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString())); }
        java.lang.reflect.Method run=Class.forName("javax.tools.Tool").getMethod("run",java.io.InputStream.class,java.io.OutputStream.class,java.io.OutputStream.class,String[].class);
        assertEquals(0,run.invoke(compiler,null,null,null,args.toArray(new String[0])));
    }
}
