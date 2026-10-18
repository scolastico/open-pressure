package org.openpressure.omronsandbox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openpressure.protocol.fixture.Fixture;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Runs every shared fixture (or {@code -Dfixture=<name>}) through Omron's code, each in a fresh class loader so
 * Omron's static singletons and Robolectric's sandbox start clean, exactly as in a new app process.
 */
@RunWith(Parameterized.class)
public class OmronFixtureTest {
    private final String name;
    public OmronFixtureTest(String name) { this.name=name; }

    @Parameterized.Parameters(name="{0}")
    public static List<Object[]> fixtures() {
        String only=System.getProperty("fixture","");
        List<Object[]> result=new ArrayList<>();
        for(String name:Fixture.all()) if(only.isEmpty() || only.equals("all") || only.equals(name)) result.add(new Object[]{name});
        return result;
    }

    @Test public void omronRunsFixture() throws Exception {
        OmronApk.assumePresent();
        List<URL> urls=new ArrayList<>();
        for(String entry:System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        String previous=System.getProperty(OmronFixtureRun.FIXTURE);
        System.setProperty(OmronFixtureRun.FIXTURE,name);
        // Parent is the platform loader, so neither Robolectric nor Omron classes are shared between fixtures.
        ClassLoader context=Thread.currentThread().getContextClassLoader();
        java.security.Provider[] providers=java.security.Security.getProviders();
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),ClassLoader.getSystemClassLoader().getParent())) {
            // Robolectric discovers its shadow providers through the context class loader.
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> core=loader.loadClass("org.junit.runner.JUnitCore");
            Object result=core.getMethod("runClasses",Class[].class).invoke(null,(Object)new Class<?>[]{loader.loadClass(OmronFixtureRun.class.getName())});
            StringBuilder failures=new StringBuilder();
            for(Object failure:(List<?>)result.getClass().getMethod("getFailures").invoke(result)) failures.append(failure.getClass().getMethod("getTrace").invoke(failure));
            assertTrue(name+": "+failures,(Boolean)result.getClass().getMethod("wasSuccessful").invoke(result));
            assertEquals(name+" ran",0,(int)(Integer)result.getClass().getMethod("getIgnoreCount").invoke(result));
        } finally {
            Thread.currentThread().setContextClassLoader(context);
            // Robolectric installs or replaces JVM-wide security providers from the child loader; they break once it
            // closes, so the original provider list is restored exactly.
            for(java.security.Provider p:java.security.Security.getProviders()) java.security.Security.removeProvider(p.getName());
            for(java.security.Provider p:providers) java.security.Security.addProvider(p);
            if(previous==null) System.clearProperty(OmronFixtureRun.FIXTURE); else System.setProperty(OmronFixtureRun.FIXTURE,previous);
        }
    }
}
