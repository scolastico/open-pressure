package org.openpressure.omronsandbox;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * The stack map repair of converted classes, on synthetic classes shaped like dex2jar output: no stack map frames,
 * too small maximum stack sizes, and type merges that need the converted classes' own hierarchy.
 */
public class ExtractOmronRepairTest {
    @Test public void dex2jarShapedClassesVerifyAfterRepair() throws Exception {
        Map<String,byte[]> classes=hierarchy("syn/");
        classes.put("syn/Merge",merge("syn/"));
        try { load(classes,"syn/Merge"); fail("the unrepaired class must not pass verification"); }
        catch(VerifyError expected) {}
        ExtractOmron.Repair repair=ExtractOmron.repair(classes,classes,Collections.emptyList());
        Class<?> merge=load(repair.classes,"syn/Merge");
        assertEquals("merged A and B as their common Base",1,merge.getMethod("pick",boolean.class).invoke(null,true));
        assertEquals(2,merge.getMethod("pick",boolean.class).invoke(null,false));
        assertEquals("maximum stack size recomputed",6,merge.getMethod("wide").invoke(null));
        assertTrue(repair.stubbed.isEmpty());
        assertTrue(repair.unverified.isEmpty());
        assertTrue(repair.unknownTypes.isEmpty());
    }

    @Test public void methodTheVerifierRejectsIsStubbedOutsideExecutedPackages() throws Exception {
        Map<String,byte[]> classes=new HashMap<>();
        classes.put("syn/Broken",broken("syn/Broken"));
        ExtractOmron.Repair repair=ExtractOmron.repair(classes,classes,Collections.emptyList());
        assertEquals(Collections.singleton("syn/Broken.bad()Ljava/lang/Object;"),repair.stubbed);
        Class<?> broken=load(repair.classes,"syn/Broken");
        assertEquals("other methods keep their code",5,broken.getMethod("good").invoke(null));
        try { broken.getMethod("bad").invoke(null); fail("stub returned"); }
        catch(InvocationTargetException e) { assertTrue(e.getCause() instanceof UnsupportedOperationException); }
        assertTrue(repair.summary().contains("syn/Broken.bad()"));
    }

    @Test public void unrepairableMethodInAnExecutedPackageFailsTheExtraction() throws Exception {
        String name=ExtractOmron.EXECUTED[0]+"Broken";
        Map<String,byte[]> classes=new HashMap<>();
        classes.put(name,broken(name));
        try { ExtractOmron.repair(classes,classes,Collections.emptyList()); fail("executed code stubbed"); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains(name+".bad()")); }
    }

    /** Base with value() 0, and A and B overriding it with 1 and 2; known only from these class files. */
    private static Map<String,byte[]> hierarchy(String pkg) {
        Map<String,byte[]> classes=new HashMap<>();
        classes.put(pkg+"Base",subclass(pkg+"Base","java/lang/Object",0));
        classes.put(pkg+"A",subclass(pkg+"A",pkg+"Base",1));
        classes.put(pkg+"B",subclass(pkg+"B",pkg+"Base",2));
        return classes;
    }
    private static byte[] subclass(String name,String parent,int value) {
        ClassWriter w=new ClassWriter(0);
        w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,parent,null);
        MethodVisitor init=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD,0); init.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(1,1); init.visitEnd();
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"value","()I",null,null);
        m.visitCode(); m.visitLdcInsn(value); m.visitInsn(Opcodes.IRETURN); m.visitMaxs(1,1); m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
    /** pick(flag) calls value() on new A() or new B() after a branch merge; wide() needs three stack slots. */
    private static byte[] merge(String pkg) {
        ClassWriter w=new ClassWriter(0);
        w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,pkg+"Merge",null,"java/lang/Object",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"pick","(Z)I",null,null);
        Label other=new Label(),join=new Label();
        m.visitCode();
        m.visitVarInsn(Opcodes.ILOAD,0); m.visitJumpInsn(Opcodes.IFEQ,other);
        m.visitTypeInsn(Opcodes.NEW,pkg+"A"); m.visitInsn(Opcodes.DUP); m.visitMethodInsn(Opcodes.INVOKESPECIAL,pkg+"A","<init>","()V",false);
        m.visitJumpInsn(Opcodes.GOTO,join);
        m.visitLabel(other);
        m.visitTypeInsn(Opcodes.NEW,pkg+"B"); m.visitInsn(Opcodes.DUP); m.visitMethodInsn(Opcodes.INVOKESPECIAL,pkg+"B","<init>","()V",false);
        m.visitLabel(join);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,pkg+"Base","value","()I",false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(1,1); // too small, like dex2jar's output
        m.visitEnd();
        MethodVisitor wide=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"wide","()I",null,null);
        wide.visitCode();
        wide.visitInsn(Opcodes.ICONST_1); wide.visitInsn(Opcodes.ICONST_2); wide.visitInsn(Opcodes.ICONST_3);
        wide.visitInsn(Opcodes.IADD); wide.visitInsn(Opcodes.IADD); wide.visitInsn(Opcodes.IRETURN);
        wide.visitMaxs(1,0);
        wide.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
    /** bad() returns an int as an object, which the verifier rejects whatever the frames; good() returns 5. */
    private static byte[] broken(String name) {
        ClassWriter w=new ClassWriter(0);
        w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        MethodVisitor bad=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"bad","()Ljava/lang/Object;",null,null);
        bad.visitCode(); bad.visitInsn(Opcodes.ICONST_0); bad.visitInsn(Opcodes.ARETURN); bad.visitMaxs(1,0); bad.visitEnd();
        MethodVisitor good=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"good","()I",null,null);
        good.visitCode(); good.visitInsn(Opcodes.ICONST_5); good.visitInsn(Opcodes.IRETURN); good.visitMaxs(1,0); good.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
    /** Defines the classes in a fresh loader and links the named one, which verifies it. */
    private static Class<?> load(Map<String,byte[]> classes,String name) throws ClassNotFoundException {
        ClassLoader loader=new ClassLoader(ClassLoader.getSystemClassLoader().getParent()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                byte[] bytes=classes.get(n.replace('.','/'));
                if(bytes==null) throw new ClassNotFoundException(n);
                return defineClass(n,bytes,0,bytes.length);
            }
        };
        Class<?> c=Class.forName(name.replace('/','.'),false,loader);
        c.getDeclaredMethods();
        return c;
    }
}
