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
 * encrypted-model emulation, and the app version. Run by the {@code omronExtract} Gradle task as a single-file
 * program with the dex2jar converter on the class path; the converter is called reflectively so the unit-test
 * class path stays free of its ASM version.
 */
public final class ExtractOmron {
    static final String HELP="Omron APK not found, see docs/omron-sandbox.md";
    /** Roots of the kept class set: Omron's communication and connectivity libraries. */
    private static final String[] ROOTS={"jp/co/omron/","com/omronhealthcare/connectivitylibrary/"};
    /**
     * Packages the Android runtime or the test tooling provides. Everything else Omron references is kept from
     * Omron's own package: R8 renamed members inside its bundled libraries (androidx, Kotlin), so Maven copies do
     * not link. Guava stays excluded because Robolectric itself depends on it.
     */
    private static final String[] PROVIDED={"java/","javax/","android/","dalvik/","org/json/","org/xmlpull/","org/w3c/","org/xml/",
        "androidx/test/","com/google/common/","com/google/thirdparty/","org/objectweb/","org/bouncycastle/","org/junit/","org/hamcrest/",
        "org/mockito/","net/bytebuddy/","org/objenesis/","com/ibm/icu/","org/robolectric/"};
    private static final Pattern DESCRIPTOR=Pattern.compile("L([\\w/$]+);");

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
        // This split is optional. Never execute a previous package's binary after a base-only update.
        Files.deleteIfExists(out.resolve("lib/arm64-v8a/libnativeOmsec.so"));
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
        java.util.Map<String,byte[]> classes=new java.util.HashMap<>();
        for(int i=0;i<dexFiles.size();i++) {
            Path part=Files.createTempFile("omron-dex-"+i,".jar");
            try {
                Object converter=dex2jar.getMethod("from",byte[].class).invoke(null,(Object)dexFiles.get(i));
                converter=dex2jar.getMethod("skipDebug",boolean.class).invoke(converter,false);
                dex2jar.getMethod("to",Path.class).invoke(converter,part);
                try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(part))) {
                    for(ZipEntry e;(e=zip.getNextEntry())!=null;)
                        if(e.getName().endsWith(".class")) classes.putIfAbsent(e.getName().substring(0,e.getName().length()-6),zip.readAllBytes());
                }
            } finally { Files.deleteIfExists(part); }
        }
        // Keep Omron's classes and everything they reference that the sandbox does not provide itself.
        java.util.ArrayDeque<String> queue=new java.util.ArrayDeque<>();
        Set<String> kept=new java.util.TreeSet<>();
        for(String name:classes.keySet()) for(String root:ROOTS) if(name.startsWith(root)) queue.add(name);
        while(!queue.isEmpty()) {
            String name=queue.poll();
            if(!kept.add(name)) continue;
            for(String ref:references(classes.get(name))) if(classes.containsKey(ref) && !kept.contains(ref) && !provided(ref)) queue.add(ref);
        }
        try(ZipOutputStream merged=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(String name:kept) { merged.putNextEntry(new ZipEntry(name+".class")); merged.write(classes.get(name)); merged.closeEntry(); }
        }
        System.out.println("Converted "+dexFiles.size()+" dex files: kept "+kept.size()+" of "+classes.size()+" classes reachable from Omron's libraries");
    }
    private static boolean provided(String name) {
        for(String prefix:PROVIDED) if(name.startsWith(prefix)) return true;
        return false;
    }
    /** Class names in the constant pool, including those only named in descriptors. */
    static Set<String> references(byte[] bytes) {
        Set<String> result=new HashSet<>();
        java.nio.ByteBuffer in=java.nio.ByteBuffer.wrap(bytes);
        in.position(8);
        int count=in.getShort()&0xffff;
        for(int i=1;i<count;i++) {
            int tag=in.get()&0xff;
            switch(tag) {
                case 1: {
                    byte[] utf=new byte[in.getShort()&0xffff]; in.get(utf);
                    String text=new String(utf,StandardCharsets.UTF_8);
                    Matcher m=DESCRIPTOR.matcher(text);
                    while(m.find()) result.add(m.group(1));
                    if(text.indexOf('/')>0 && text.indexOf(';')<0 && text.indexOf('(')<0) result.add(text.startsWith("[")?text.replaceAll("^\\[+L?",""):text);
                    break;
                }
                case 3: case 4: in.getInt(); break;
                case 5: case 6: in.getLong(); i++; break;
                case 7: case 8: case 16: case 19: case 20: in.getShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.getInt(); break;
                case 15: in.get(); in.getShort(); break;
                default: throw new IllegalStateException("unknown constant tag "+tag);
            }
        }
        return result;
    }
}
