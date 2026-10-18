package org.openpressure.omronsandbox;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Plug-in point for Omron's {@code libnativeOmsec.so} (pairing and AES-CCM for encrypted models). Emulation is a
 * later phase; until then every native call fails loudly so a fixture can never pass through a guessed result.
 * The static initializer only loads the library, so it is skipped.
 */
@Implements(className=OmronAnchors.LIB+".ohq.OHQCustomPairing")
public class ShadowOmsecNatives {
    static final String MESSAGE="native omsec not emulated yet";

    @Implementation protected static void __staticInitializer__() {}
    @Implementation protected byte[] nativeOmsecAesDecrypt(long session,byte[] data) { throw fail(); }
    @Implementation protected byte[] nativeOmsecAesEncrypt(long session,byte[] data) { throw fail(); }
    @Implementation protected byte[] nativeOmsecGetChallengeReq(long session,byte[] data) { throw fail(); }
    @Implementation protected byte[] nativeOmsecGetLtk(long session,byte[] data) { throw fail(); }
    @Implementation protected static int nativeOmsecGetMaxCommandSize() { throw fail(); }
    @Implementation protected static int nativeOmsecGetMaxDataSize() { throw fail(); }
    @Implementation protected static int nativeOmsecGetPacketHeaderSize() { throw fail(); }
    @Implementation protected byte[] nativeOmsecGetPairingReq(long session) { throw fail(); }
    @Implementation protected byte[] nativeOmsecGetStartEncReq(long session,byte[] data) { throw fail(); }
    @Implementation protected long nativeOmsecInitialize(byte[] seed) { throw fail(); }
    @Implementation protected void nativeOmsecShutdown(long session) { throw fail(); }

    private static IllegalStateException fail() { return new IllegalStateException(MESSAGE); }
}
