package org.openpressure.omronsandbox;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import java.nio.file.Path;

/**
 * Bridges Omron's JNI calls to the locally supplied ARM64 library in Unidbg. The static initializer only loads
 * the library into Android's process; skip it because the binary belongs in the emulated process instead.
 */
@Implements(className=OmronAnchors.LIB+".ohq.OHQCustomPairing")
public class ShadowOmsecNatives {
    private static OmsecEmulator emulator;
    private static Path library;
    private static byte[] random=new byte[0];

    static synchronized void configure(Path path,byte[] entropy) {
        reset();
        library=path;
        random=entropy.clone();
    }

    private static synchronized OmsecEmulator runtime() {
        if(emulator==null) {
            Path path=library;
            if(path==null) path=java.nio.file.Paths.get(System.getProperty("omron.dir","build/omron"),"lib/arm64-v8a/libnativeOmsec.so");
            emulator=new OmsecEmulator(path,random);
        }
        return emulator;
    }

    @Resetter public static synchronized void reset() {
        try { if(emulator!=null) emulator.close(); }
        finally { emulator=null; library=null; java.util.Arrays.fill(random,(byte)0); random=new byte[0]; }
    }

    @Implementation protected static void __staticInitializer__() {}
    @Implementation protected byte[] nativeOmsecAesDecrypt(long session,byte[] data) { return runtime().bytes("nativeOmsecAesDecrypt",session,data); }
    @Implementation protected byte[] nativeOmsecAesEncrypt(long session,byte[] data) { return runtime().bytes("nativeOmsecAesEncrypt",session,data); }
    @Implementation protected byte[] nativeOmsecGetChallengeReq(long session,byte[] data) { return runtime().bytes("nativeOmsecGetChallengeReq",session,data); }
    @Implementation protected byte[] nativeOmsecGetLtk(long session,byte[] data) { return runtime().bytes("nativeOmsecGetLtk",session,data); }
    @Implementation protected static int nativeOmsecGetMaxCommandSize() { return runtime().size("nativeOmsecGetMaxCommandSize"); }
    @Implementation protected static int nativeOmsecGetMaxDataSize() { return runtime().size("nativeOmsecGetMaxDataSize"); }
    @Implementation protected static int nativeOmsecGetPacketHeaderSize() { return runtime().size("nativeOmsecGetPacketHeaderSize"); }
    @Implementation protected byte[] nativeOmsecGetPairingReq(long session) { return runtime().bytes("nativeOmsecGetPairingReq",session,null); }
    @Implementation protected byte[] nativeOmsecGetStartEncReq(long session,byte[] data) { return runtime().bytes("nativeOmsecGetStartEncReq",session,data); }
    @Implementation protected long nativeOmsecInitialize(byte[] savedState) { return runtime().initialize(savedState); }
    @Implementation protected void nativeOmsecShutdown(long session) { runtime().shutdown(session); }
}
