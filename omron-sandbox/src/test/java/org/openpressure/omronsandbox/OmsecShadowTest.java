package org.openpressure.omronsandbox;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Verifies Robolectric dispatch from Omron's real private native methods, including static JNI methods. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=ShadowOmsecNatives.class,
    instrumentedPackages={"jp.co.omron.healthcare.communicationlibrary","com.omronhealthcare.connectivitylibrary"})
public class OmsecShadowTest {
    @Test public void omronsNativeDeclarationsReachTheArm64Library() throws Exception {
        Path library=OmronApk.assumePresent().dir.resolve("lib/arm64-v8a/libnativeOmsec.so");
        Assume.assumeTrue("native tests need the arm64-v8a split",Files.isRegularFile(library));
        ShadowOmsecNatives.configure(library,new byte[0]);
        Class<?> type=Class.forName(OmronAnchors.LIB+".ohq.OHQCustomPairing");
        Class<?> deviceType=Class.forName(OmronAnchors.LIB+".ble.BLEDevice");
        // Construct Omron's real pairing object; only its unused Bluetooth dependency is replaced.
        Object pairing=type.getConstructor(deviceType,byte[].class).newInstance(org.mockito.Mockito.mock(deviceType),null);
        assertEquals(231,invoke(pairing,"nativeOmsecGetMaxDataSize",new Class<?>[0]));
        long session=(Long)invoke(pairing,"nativeOmsecInitialize",new Class<?>[]{byte[].class},new byte[80]);
        assertNotEquals(0,session);
        try {
            byte[] start=(byte[])invoke(pairing,"nativeOmsecGetStartEncReq",new Class<?>[]{long.class,byte[].class},session,null);
            assertNotNull(start);
            assertEquals(45,start.length);
            assertEquals(5,start[0]);
        } finally { invoke(pairing,"nativeOmsecShutdown",new Class<?>[]{long.class},session); }
    }

    private static Object invoke(Object receiver,String name,Class<?>[] parameters,Object... args) throws Exception {
        Method method=receiver.getClass().getDeclaredMethod(name,parameters);
        method.setAccessible(true);
        return method.invoke(receiver,args);
    }
}
