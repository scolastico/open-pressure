package org.openpressure.omronsandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Extracts what the sandbox needs from a locally supplied Omron Connect package (one .xapk, or base and split
 * .apk files): converted Omron classes, the model catalog and memory-map configs, the native library for the
 * later encrypted-model phase, and the app version. Run by the {@code omronExtract} Gradle task as a single-file
 * program with the dex2jar converter on the class path; the converter is called reflectively so the unit-test
 * class path stays free of its ASM version.
 */
public final class ExtractOmron {
    static final String HELP="Omron APK not found, see docs/omron-sandbox.md";
    /** Packages the sandbox already provides; Omron's bundled copies would shadow them. */
    private static final String[] EXCLUDED={"kotlin/","kotlinx/","androidx/","android/","org/junit/","org/hamcrest/",
        "com/google/android/material/","org/intellij/","org/jetbrains/","META-INF/"};

    private ExtractOmron() {}

    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("usage: ExtractOmron <apk-dir> <out-dir> [--no-dex]");
        Path input=Paths.get(args[0]),out=Paths.get(args[1]);
        boolean dex=!(args.length>2 && args[2].equals("--no-dex"));
        List<byte[]> apks=new ArrayList<>();
        String version=null;
        try(Stream<Path> files=Files.list(input)) {
            for(Path file:(Iterable<Path>)files.sorted()::iterator) {
                String name=file.getFileName().toString();
                if(name.endsWith(".xapk")) {
                    try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(file))) {
                        for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                            if(e.getName().endsWith(".apk")) apks.add(zip.readAllBytes());
                            else if(e.getName().equals("manifest.json") || e.getName().equals("info.json")) version=version(new String(zip.readAllBytes(),StandardCharsets.UTF_8));
                        }
                    }
                } else if(name.endsWith(".apk")) apks.add(Files.readAllBytes(file));
            }
        }
        if(apks.isEmpty()) throw new IllegalStateException(HELP+" (no .xapk or .apk in "+input+")");
        Files.createDirectories(out);
        List<byte[]> dexFiles=new ArrayList<>();
        boolean catalog=false,configs=false;
        for(byte[] apk:apks) {
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(apk))) {
                for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                    String name=e.getName();
                    if(name.matches("classes\\d*\\.dex")) { if(dex) dexFiles.add(zip.readAllBytes()); }
                    else if(name.equals("res/raw/device_category.zip")) { Files.write(out.resolve("device_category.zip"),zip.readAllBytes()); configs=true; }
                    else if(name.equals("assets/Device/device_models.json")) { Files.write(out.resolve("device_models.json"),zip.readAllBytes()); catalog=true; }
                    else if(name.equals("lib/arm64-v8a/libnativeOmsec.so")) {
                        Files.createDirectories(out.resolve("lib/arm64-v8a"));
                        Files.write(out.resolve("lib/arm64-v8a/libnativeOmsec.so"),zip.readAllBytes());
                    }
                }
            }
        }
        if(!catalog || !configs) throw new IllegalStateException("Omron package lacks device_models.json or device_category.zip; "+HELP);
        if(dex) {
            if(dexFiles.isEmpty()) throw new IllegalStateException("Omron package contains no classes; "+HELP);
            convert(dexFiles,out.resolve("omron-classes.jar"));
        }
        Files.write(out.resolve("version.txt"),((version==null?"unknown":version)+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Extracted Omron "+(version==null?"(unknown version)":version)+" into "+out);
    }

    static String version(String json) {
        String name=field(json,"version_name"),code=field(json,"version_code");
        return name==null?null:name+(code==null?"":" ("+code+")");
    }
    private static String field(String json,String key) {
        Matcher m=Pattern.compile("\""+key+"\"\\s*:\\s*\"?([^\",}]+)\"?").matcher(json);
        return m.find()?m.group(1).trim():null;
    }

    private static void convert(List<byte[]> dexFiles,Path jar) throws Exception {
        Class<?> dex2jar=Class.forName("com.googlecode.d2j.dex.Dex2jar");
        Set<String> seen=new HashSet<>();
        int skipped=0;
        try(ZipOutputStream merged=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(int i=0;i<dexFiles.size();i++) {
                Path part=Files.createTempFile("omron-dex-"+i,".jar");
                try {
                    Object converter=dex2jar.getMethod("from",byte[].class).invoke(null,(Object)dexFiles.get(i));
                    converter=dex2jar.getMethod("skipDebug",boolean.class).invoke(converter,false);
                    dex2jar.getMethod("to",Path.class).invoke(converter,part);
                    try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(part))) {
                        for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                            String name=e.getName();
                            if(e.isDirectory() || excluded(name)) { if(!e.isDirectory()) skipped++; continue; }
                            if(!seen.add(name)) continue;
                            merged.putNextEntry(new ZipEntry(name));
                            merged.write(zip.readAllBytes());
                            merged.closeEntry();
                        }
                    }
                } finally { Files.deleteIfExists(part); }
            }
        }
        System.out.println("Converted "+dexFiles.size()+" dex files: "+seen.size()+" classes kept, "+skipped+" provided by the sandbox");
    }
    private static boolean excluded(String name) {
        for(String prefix:EXCLUDED) if(name.startsWith(prefix)) return true;
        return false;
    }
}
