package org.openpressure.omronsandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assume;

/** The extracted local Omron package in {@code omron-sandbox/build/omron}, if a developer supplied one. */
public final class OmronApk {
    public final Path dir,classesJar,deviceCategoryZip,deviceModelsJson;
    public final String version;

    private OmronApk(Path dir,String version) {
        this.dir=dir; this.version=version;
        classesJar=dir.resolve("omron-classes.jar"); deviceCategoryZip=dir.resolve("device_category.zip"); deviceModelsJson=dir.resolve("device_models.json");
    }
    /** Null when no package was extracted, or when the package it came from is no longer in the apk folder. */
    public static OmronApk locate() {
        return locate(Paths.get(System.getProperty("omron.dir","build/omron")),Paths.get(System.getProperty("omron.apk.dir","apk")));
    }
    static OmronApk locate(Path dir,Path apkDir) {
        if(!hasPackage(apkDir)) return null;
        Path version=dir.resolve("version.txt");
        if(!Files.isRegularFile(version) || !Files.isRegularFile(dir.resolve("omron-classes.jar"))) return null;
        try { return new OmronApk(dir,new String(Files.readAllBytes(version),StandardCharsets.UTF_8).trim()); }
        catch(IOException unreadable) { return null; }
    }
    private static boolean hasPackage(Path apkDir) {
        try(java.util.stream.Stream<Path> files=Files.list(apkDir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".apk") || f.getFileName().toString().endsWith(".xapk"));
        } catch(IOException missing) { return false; }
    }
    /** Skips the calling test when no Omron package is available. */
    public static OmronApk assumePresent() {
        OmronApk apk=locate();
        Assume.assumeTrue(ExtractOmron.HELP,apk!=null);
        return apk;
    }
}
