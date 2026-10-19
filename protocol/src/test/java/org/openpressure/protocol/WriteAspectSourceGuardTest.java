package org.openpressure.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Source guard: only the reviewed call sites may consult the write-aspect gate, a stored
 * untested-write override, the developer-chosen clock-time permission or an experimental-path switch. A new caller must be
 * reviewed and added to the allow-list.
 */
public final class WriteAspectSourceGuardTest {
    private static final String[] GUARDED={"requireWriteAspect(","allowsUntested(","allowsChosenClockTime(","allowsExperimental("};
    /** Repository-relative files or directories (ending in '/') that may contain the guarded calls. */
    private static final String[] ALLOWED={
        "protocol/src/main/java/org/openpressure/protocol/DeviceWritePolicy.java",
        "app/src/main/java/org/openpressure/app/developer/",
        "app/src/main/java/org/openpressure/app/ble/MonitorService.java"
    };
    /**
     * MonitorService reads the stored override, the chosen-time permission and the experimental switches through exactly
     * one private helper each; it never calls requireWriteAspect itself.
     */
    private static final String MONITOR_SERVICE="app/src/main/java/org/openpressure/app/ble/MonitorService.java";
    private static int checks;
    public static void main(String[] args) { System.out.println("Write aspect source guard checks: "+run()); }
    public static int run() {
        checks=0;
        Path root=repositoryRoot();
        List<String> violations=new ArrayList<>();
        int definitions=0,monitorOverrides=-1,monitorChosenTimes=-1,monitorExperiments=-1;
        for(String tree:new String[]{"app/src/main","protocol/src/main"}) {
            try(Stream<Path> files=Files.walk(root.resolve(tree))) {
                for(Path file:(Iterable<Path>)files.filter(Files::isRegularFile)::iterator) {
                    String relative=root.relativize(file).toString().replace('\\','/');
                    String source=read(file);
                    if(relative.equals(MONITOR_SERVICE)) {
                        monitorOverrides=occurrences(source,"allowsUntested(");
                        monitorChosenTimes=occurrences(source,"allowsChosenClockTime(");
                        monitorExperiments=occurrences(source,"allowsExperimental(");
                        if(source.contains("requireWriteAspect(")) violations.add(relative+": requireWriteAspect(");
                    }
                    for(String call:GUARDED) {
                        if(!source.contains(call)) continue;
                        if(allowed(relative)) { if(relative.endsWith("DeviceWritePolicy.java")) definitions++; continue; }
                        violations.add(relative+": "+call);
                    }
                    checks++;
                }
            } catch(IOException e) { throw new UncheckedIOException(e); }
        }
        if(!violations.isEmpty()) throw new AssertionError("write-aspect gate used outside the reviewed allow-list: "+violations);
        if(definitions==0) throw new AssertionError("source guard did not find the requireWriteAspect definition");
        if(monitorOverrides!=1) throw new AssertionError("MonitorService must read the untested-write override exactly once, found "+monitorOverrides);
        if(monitorChosenTimes!=1) throw new AssertionError("MonitorService must read the chosen clock-time permission exactly once, found "+monitorChosenTimes);
        if(monitorExperiments!=1) throw new AssertionError("MonitorService must read the experimental switches exactly once, found "+monitorExperiments);
        if(!allowed("app/src/main/java/org/openpressure/app/developer/DeveloperOptions.java") || allowed("app/src/main/java/org/openpressure/app/MonitorService.java")
                || allowed("protocol/src/main/java/org/openpressure/protocol/DeviceWritePolicyHelper.java")) throw new AssertionError("allow-list matching");
        return ++checks;
    }
    private static int occurrences(String source,String call) {
        int count=0;
        for(int at=source.indexOf(call);at>=0;at=source.indexOf(call,at+call.length())) count++;
        return count;
    }
    private static boolean allowed(String relative) {
        for(String entry:ALLOWED) if(entry.endsWith("/")?relative.startsWith(entry):relative.equals(entry)) return true;
        return false;
    }
    /** Works from the repository root (tools/test.sh) and from the protocol project (Gradle). */
    private static Path repositoryRoot() {
        for(Path dir=Paths.get(System.getProperty("user.dir")).toAbsolutePath();dir!=null;dir=dir.getParent())
            if(Files.isDirectory(dir.resolve("app/src/main")) && Files.isDirectory(dir.resolve("protocol/src/main"))) return dir;
        throw new AssertionError("source guard could not locate app/src/main and protocol/src/main");
    }
    private static String read(Path file) {
        try { return new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
}
