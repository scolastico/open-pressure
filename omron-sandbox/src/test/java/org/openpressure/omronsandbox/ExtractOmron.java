package org.openpressure.omronsandbox;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Extracts what the sandbox needs from a locally supplied Omron Connect package (one .xapk, or base and split
 * .apk files): converted Omron classes, the model catalog and memory-map configs, the native library for the
 * encrypted-model emulation, and the app version. Run by the {@code omronExtract} Gradle task as a single-file
 * program with the dex2jar converter on the class path; the converter is called reflectively so the unit-test
 * class path stays free of dex2jar. Both class paths carry the same ASM version, which {@link #repair} uses.
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
    /** Packages the sandbox executes (Robolectric's instrumented packages): a method there must never be stubbed. */
    static final String[] EXECUTED={"jp/co/omron/healthcare/communicationlibrary/","com/omronhealthcare/connectivitylibrary/"};
    /** Class path entries (android.jar) that resolve Android types while repairing and verifying the classes. */
    static final String LIBRARIES="omron.libraries";
    private static final Pattern LOCATION=Pattern.compile("Location:\\s+([^\\s(]+)\\.([^\\s.(]+)(\\([^\\s]+) @");

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
        Map<String,byte[]> keptClasses=new TreeMap<>();
        for(String name:kept) keptClasses.put(name,classes.get(name));
        List<Path> libraries=new ArrayList<>();
        for(String entry:System.getProperty(LIBRARIES,"").split(File.pathSeparator)) if(!entry.isEmpty()) libraries.add(Paths.get(entry));
        Repair repair=repair(keptClasses,classes,libraries);
        try(ZipOutputStream merged=new ZipOutputStream(Files.newOutputStream(jar))) {
            for(Map.Entry<String,byte[]> e:repair.classes.entrySet()) { merged.putNextEntry(new ZipEntry(e.getKey()+".class")); merged.write(e.getValue()); merged.closeEntry(); }
        }
        System.out.println("Converted "+dexFiles.size()+" dex files: kept "+kept.size()+" of "+classes.size()+" classes reachable from Omron's libraries");
        System.out.println(repair.summary());
    }

    /** Outcome of {@link #repair}: the rewritten classes and what could not be repaired or checked. */
    static final class Repair {
        final Map<String,byte[]> classes=new TreeMap<>();
        /** Methods whose body was replaced by a throw, as owner.name+descriptor. */
        final Set<String> stubbed=new LinkedHashSet<>();
        /** Types referenced in a type merge whose class file was not found; their common supertype was taken as Object. */
        final Set<String> unknownTypes=new java.util.TreeSet<>();
        /** Classes the JVM could not link at extraction because a class they need is missing (for example Guava). */
        final Set<String> unverified=new java.util.TreeSet<>();
        String summary() {
            StringBuilder s=new StringBuilder("Recomputed stack map frames and maximum stack sizes of "+classes.size()+" classes; verified "
                +(classes.size()-unverified.size())+", "+unverified.size()+" not linkable without the test class path; "+unknownTypes.size()+" unknown types");
            for(String m:stubbed) s.append("\nStubbed unrepairable method ").append(m);
            return s.toString();
        }
    }

    /**
     * dex2jar writes no stack map frames and sometimes too small maximum stack sizes. HotSpot only notices the latter
     * when it builds an oop map for an interpreted frame of such a method (for example during a garbage collection)
     * and then aborts the whole JVM. Rewrite every class with frames and stack sizes computed by ASM, so the sandbox
     * JVM can keep bytecode verification on, and verify the result here with the JVM's own verifier. A method that
     * still fails is replaced by a throw of {@link UnsupportedOperationException}; in a package the sandbox executes
     * that fails the extraction instead.
     *
     * @param classes the classes to rewrite, by internal name
     * @param known every converted class, used with {@code libraries} and the JDK to answer type-hierarchy questions
     *     from class files without loading or initializing any of them
     */
    static Repair repair(Map<String,byte[]> classes,Map<String,byte[]> known,List<Path> libraries) throws IOException {
        URL[] urls=new URL[libraries.size()];
        for(int i=0;i<urls.length;i++) urls[i]=libraries.get(i).toUri().toURL();
        Repair result=new Repair();
        // The platform class loader (JDK types), not the class path: dex2jar and ASM must not answer hierarchy questions.
        try(URLClassLoader libs=new URLClassLoader(urls,ClassLoader.getSystemClassLoader().getParent())) {
            Hierarchy hierarchy=new Hierarchy(known,libs,result.unknownTypes);
            for(Map.Entry<String,byte[]> e:classes.entrySet()) result.classes.put(e.getKey(),rewrite(e.getValue(),hierarchy,result.stubbed));
            // Each pass reports at most one failing method per class. A method that fails again after it was stubbed
            // ends the extraction, so this terminates.
            for(Set<String> failed;!(failed=verify(result,libs)).isEmpty();) {
                Set<String> owners=new java.util.TreeSet<>();
                for(String method:failed) { result.stubbed.add(method); owners.add(method.substring(0,method.indexOf('.'))); }
                for(String owner:owners) result.classes.put(owner,rewrite(classes.get(owner),hierarchy,result.stubbed));
            }
        }
        return result;
    }

    /** Links every class with verification on; returns the failing method (owner.name+descriptor) per failing class. */
    private static Set<String> verify(Repair result,ClassLoader libs) {
        Set<String> failed=new java.util.TreeSet<>();
        result.unverified.clear();
        ClassLoader loader=new ClassLoader(libs) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes=result.classes.get(name.replace('.','/'));
                if(bytes==null) throw new ClassNotFoundException(name);
                return defineClass(name,bytes,0,bytes.length);
            }
        };
        for(String name:result.classes.keySet()) {
            try { Class.forName(name.replace('/','.'),false,loader).getDeclaredMethods(); } // links, and so verifies, the class
            catch(VerifyError e) {
                Matcher m=LOCATION.matcher(String.valueOf(e.getMessage()));
                String method=m.find()?m.group(1)+"."+m.group(2)+m.group(3):null;
                if(method==null || !result.classes.containsKey(m.group(1)) || result.stubbed.contains(method))
                    throw new IllegalStateException("Cannot repair converted Omron class "+name+": "+e.getMessage(),e);
                failed.add(method);
            }
            catch(LinkageError|TypeNotPresentException e) { result.unverified.add(name); }
            catch(ClassNotFoundException e) { throw new IllegalStateException(e); }
        }
        return failed;
    }

    /** Writes a class with computed frames and stack sizes, stubbing the listed methods and those ASM cannot analyse. */
    private static byte[] rewrite(byte[] original,Hierarchy hierarchy,Set<String> stubbed) {
        ClassNode node=new ClassNode();
        new ClassReader(original).accept(node,ClassReader.SKIP_FRAMES);
        int found=0;
        for(MethodNode method:node.methods) if(stubbed.contains(node.name+"."+method.name+method.desc)) { stub(node,method,stubbed); found++; }
        for(String method:stubbed) if(method.startsWith(node.name+".") && method.indexOf('.',node.name.length()+1)<0) found--;
        if(found!=0) throw new IllegalStateException("Cannot find the failing method of converted Omron class "+node.name);
        try { return frames(node,hierarchy); }
        catch(RuntimeException whole) {
            // Find the methods ASM cannot analyse by writing each one alone.
            for(MethodNode method:node.methods) {
                if(method.instructions.size()==0) continue;
                ClassNode single=new ClassNode();
                node.accept(single);
                single.methods.removeIf(other -> !(other.name.equals(method.name) && other.desc.equals(method.desc)));
                try { frames(single,hierarchy); } catch(RuntimeException e) { stub(node,method,stubbed); }
            }
            return frames(node,hierarchy);
        }
    }
    private static byte[] frames(ClassNode node,Hierarchy hierarchy) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a,String b) { return hierarchy.commonSuperClass(a,b); }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
    private static void stub(ClassNode owner,MethodNode method,Set<String> stubbed) {
        String name=owner.name+"."+method.name+method.desc;
        for(String executed:EXECUTED) if(owner.name.startsWith(executed))
            throw new IllegalStateException("Cannot convert "+name+", which the sandbox executes; see docs/omron-sandbox.md");
        InsnList code=new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW,"java/lang/UnsupportedOperationException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("OpenPressure sandbox could not convert "+name));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/UnsupportedOperationException","<init>","(Ljava/lang/String;)V",false));
        code.add(new InsnNode(Opcodes.ATHROW));
        method.instructions=code;
        method.tryCatchBlocks.clear();
        if(method.localVariables!=null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations=null; method.invisibleLocalVariableAnnotations=null;
        method.visibleTypeAnnotations=null; method.invisibleTypeAnnotations=null;
        stubbed.add(name);
    }

    /** Type hierarchy read from class files (converted classes, libraries, the JDK) instead of loaded classes. */
    static final class Hierarchy {
        private final Map<String,byte[]> known;
        private final ClassLoader libraries;
        private final Set<String> unknown;
        private final Map<String,ClassReader> readers=new HashMap<>();
        Hierarchy(Map<String,byte[]> known,ClassLoader libraries,Set<String> unknown) { this.known=known; this.libraries=libraries; this.unknown=unknown; }

        String commonSuperClass(String a,String b) {
            if(assignable(a,b)) return a;
            if(assignable(b,a)) return b;
            ClassReader ra=reader(a),rb=reader(b);
            if(ra==null || rb==null || isInterface(ra) || isInterface(rb)) return "java/lang/Object";
            for(String c=ra.getSuperName();c!=null;c=reader(c)==null?null:reader(c).getSuperName()) if(assignable(c,b)) return c;
            return "java/lang/Object";
        }
        /** Whether a value of type {@code from} can be stored in {@code to}. */
        boolean assignable(String to,String from) {
            if(to.equals(from) || to.equals("java/lang/Object")) return true;
            ClassReader r=reader(from);
            if(r==null) return false;
            if(r.getSuperName()!=null && assignable(to,r.getSuperName())) return true;
            for(String i:r.getInterfaces()) if(assignable(to,i)) return true;
            return false;
        }
        private static boolean isInterface(ClassReader r) { return (r.getAccess()&Opcodes.ACC_INTERFACE)!=0; }
        private ClassReader reader(String name) {
            if(readers.containsKey(name)) return readers.get(name);
            // The sandbox loads Android and JDK types from the platform, everything else from the converted classes.
            byte[] bytes=null;
            try(InputStream in=libraries.getResourceAsStream(name+".class")) { if(in!=null) bytes=in.readAllBytes(); }
            catch(IOException e) { throw new UncheckedIOException(e); }
            if(bytes==null) bytes=known.get(name);
            if(bytes==null) unknown.add(name);
            ClassReader r=bytes==null?null:new ClassReader(bytes);
            readers.put(name,r);
            return r;
        }
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
