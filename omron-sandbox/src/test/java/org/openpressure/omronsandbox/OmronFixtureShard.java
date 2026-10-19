package org.openpressure.omronsandbox;

import org.junit.Test;
import org.openpressure.protocol.fixture.Fixture;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Runs shared fixtures (all, or the {@code -Dfixture=<name>[,<name>…]} selection) through Omron's code, each in a
 * fresh class loader so Omron's static singletons and Robolectric's sandbox start clean, exactly as in a new app
 * process. Gradle spreads test classes, not parameters, over its forks, so the fixtures are dealt round-robin to
 * {@value #SHARDS} trivial subclasses ({@code OmronFixtureShard<N>Test}); a shard whose selection is empty has no
 * parameters and runs nothing.
 */
public abstract class OmronFixtureShard {
    static final int SHARDS=8;
    private final String name;
    protected OmronFixtureShard(String name) { this.name=name; }

    /** The selected fixtures whose position in {@link Fixture#all()} modulo {@value #SHARDS} is {@code shard}. */
    static List<Object[]> fixtures(int shard) {
        String only=System.getProperty("fixture","");
        List<String> selected=only.isEmpty() || only.equals("all")?null:Arrays.asList(only.split(","));
        List<String> all=Fixture.all();
        List<Object[]> result=new ArrayList<>();
        for(int i=shard;i<all.size();i+=SHARDS) if(selected==null || selected.contains(all.get(i))) result.add(new Object[]{all.get(i)});
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
        // Not closed: Robolectric registers JVM shutdown hooks from this loader that still load classes at exit,
        // and those hooks keep the loader reachable regardless.
        URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),ClassLoader.getSystemClassLoader().getParent());
        try {
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
