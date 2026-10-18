package org.openpressure.omronsandbox;

import org.junit.Test;
import org.junit.After;
import org.junit.Assume;
import org.openpressure.protocol.Crypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import static org.junit.Assert.*;

/** Executes the locally supplied ARM64 binary, rather than a replacement crypto implementation. */
public class OmsecEmulatorTest {
    @After public void cleanup() { ShadowOmsecNatives.reset(); }

    @Test public void nativePairingProducesAnEcPublicKey() {
        configure();
        ShadowOmsecNatives nativeCalls=new ShadowOmsecNatives();
        long session=nativeCalls.nativeOmsecInitialize(null);
        assertNotEquals(0,session);
        try {
            byte[] request=nativeCalls.nativeOmsecGetPairingReq(session);
            assertNotNull(request);
            assertEquals(88,request.length);
            assertEquals(1,request[0]);
            java.math.BigInteger x=little(request,24),y=little(request,56);
            java.math.BigInteger p=new java.math.BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",16);
            java.math.BigInteger b=new java.math.BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",16);
            assertEquals("public key lies on P-256",y.multiply(y).mod(p),x.pow(3).subtract(x.multiply(java.math.BigInteger.valueOf(3))).add(b).mod(p));
        } finally { nativeCalls.nativeOmsecShutdown(session); }
    }

    @Test public void savedKeyHandshakeAuthenticatesAndExchangesCcmPackets() {
        configure();
        authenticate(false,false);
    }

    @Test public void freshPairingDerivesTheSameLtkAsTheSyntheticPeer() {
        configure();
        authenticate(true,false);
    }

    @Test public void invalidPeerChallengeNeverReturnsSavedState() {
        configure();
        authenticate(false,true);
    }

    @Test public void entropyAndNativeStateAreResetBetweenRuns() {
        configure();
        ShadowOmsecNatives calls=new ShadowOmsecNatives();
        byte[] first=calls.nativeOmsecGetPairingReq(calls.nativeOmsecInitialize(null));
        configure(); // Also closes a session abandoned by an interrupted fixture.
        byte[] second=calls.nativeOmsecGetPairingReq(calls.nativeOmsecInitialize(null));
        assertArrayEquals(first,second);
    }

    @Test public void exhaustedEntropyFailsInsteadOfFallingBackToHostRandom() {
        Path library=library();
        ShadowOmsecNatives.configure(library,new byte[]{1});
        ShadowOmsecNatives calls=new ShadowOmsecNatives();
        long session=calls.nativeOmsecInitialize(new byte[80]);
        IllegalStateException failure=assertThrows(IllegalStateException.class,() -> calls.nativeOmsecGetStartEncReq(session,null));
        assertTrue(failure.getMessage(),failure.getMessage().contains("random exhausted"));
    }

    @Test public void closedSessionsCannotReachFreedNativeMemory() {
        configure();
        ShadowOmsecNatives calls=new ShadowOmsecNatives();
        long session=calls.nativeOmsecInitialize(null);
        calls.nativeOmsecShutdown(session);
        assertThrows(IllegalStateException.class,() -> calls.nativeOmsecGetPairingReq(session));
        assertThrows(IllegalStateException.class,() -> calls.nativeOmsecShutdown(session));
    }

    @Test public void missingArm64SplitHasAnActionableError() {
        ShadowOmsecNatives.configure(Path.of("build","missing-omsec-test","libnativeOmsec.so"),new byte[0]);
        IllegalStateException failure=assertThrows(IllegalStateException.class,ShadowOmsecNatives::nativeOmsecGetMaxDataSize);
        assertTrue(failure.getMessage(),failure.getMessage().contains("arm64-v8a split"));
    }

    private static void authenticate(boolean fresh,boolean badChallenge) {
        ShadowOmsecNatives calls=new ShadowOmsecNatives();
        byte[] ltk=hex("000102030405060708090a0b0c0d0e0f");
        byte[] saved=new byte[80]; System.arraycopy(ltk,0,saved,0,16);
        long session=calls.nativeOmsecInitialize(fresh?null:saved);
        byte[] start;
        if(fresh) {
            byte[] pair=calls.nativeOmsecGetPairingReq(session);
            byte[] peer=new byte[88]; peer[0]=(byte)0x81;
            Arrays.fill(peer,1,8,(byte)0xa1); Arrays.fill(peer,8,24,(byte)0xb2);
            System.arraycopy(hex("96c298d84539a1f4a033eb2d817d0377f240a463e5e6bcf847422ce1f2d1176b"),0,peer,24,32);
            System.arraycopy(hex("f551bf376840b6cbce5e316b5733ce2b169e0f7c4aebe78e9b7f1afee242e34f"),0,peer,56,32);
            start=calls.nativeOmsecGetStartEncReq(session,peer);
            // Peer scalar = 1: ECDH shared X is the phone's public X, in big-endian order for the KDF.
            byte[] shared=little(pair,24).toByteArray();
            byte[] x=new byte[32];
            System.arraycopy(shared,Math.max(0,shared.length-32),x,Math.max(0,32-shared.length),Math.min(32,shared.length));
            byte[] kdf=new byte[53];
            System.arraycopy(new byte[]{1,'b','t','l','e'},0,kdf,0,5);
            System.arraycopy(pair,8,kdf,5,16); Arrays.fill(kdf,21,37,(byte)0xb2);
            System.arraycopy(pair,1,kdf,37,7); Arrays.fill(kdf,44,51,(byte)0xa1); kdf[51]=1;
            ltk=Crypto.cmac(Crypto.cmac(hex("6c888391aaf5a53860370bdb5a6083be"),x),kdf);
        } else start=calls.nativeOmsecGetStartEncReq(session,null);
        assertNotNull(start);
        assertEquals(45,start.length);
        assertEquals(5,start[0]);
        byte[] response=new byte[45]; response[0]=(byte)0x85;
        Arrays.fill(response,1,17,(byte)7); Arrays.fill(response,17,25,(byte)8); Arrays.fill(response,25,29,(byte)9);
        byte[] skd=new byte[16]; Arrays.fill(skd,0,8,(byte)8); System.arraycopy(start,17,skd,8,8);
        byte[] key=Crypto.cmac(ltk,skd);
        byte[] nonce=new byte[13]; nonce[4]=(byte)0x80; Arrays.fill(nonce,5,9,(byte)9); System.arraycopy(start,25,nonce,9,4);
        byte[] challenge=calls.nativeOmsecGetChallengeReq(session,response);
        assertNotNull(challenge);
        assertEquals(6,challenge[0]);
        byte[] opened=Crypto.open(key,nonce,new byte[4],Arrays.copyOfRange(challenge,1,challenge.length));
        byte[] expected=new byte[32]; Arrays.fill(expected,0,16,(byte)7);
        assertArrayEquals(expected,Arrays.copyOf(opened,32));
        byte[] peerPlain=new byte[36]; System.arraycopy(start,1,peerPlain,0,16);
        if(badChallenge) peerPlain[0]^=1;
        nonce[4]=0;
        byte[] finish=concat(new byte[]{(byte)0x86},Crypto.seal(key,nonce,new byte[4],peerPlain));
        byte[] state=calls.nativeOmsecGetLtk(session,finish);
        if(badChallenge) { assertNull(state); return; }
        assertNotNull(state);
        if(fresh) {
            assertEquals("native persistence blob, not just the 16-byte key",80,state.length);
            assertArrayEquals(ltk,Arrays.copyOf(state,16));
        } else assertEquals("reconnect has no new key to persist",0,state.length);
        byte[] plain=hex("102030");
        byte[] encrypted=calls.nativeOmsecAesEncrypt(session,plain);
        assertEquals(plain.length+13,encrypted.length);
        assertArrayEquals(hex("c001000000"),Arrays.copyOf(encrypted,5));
        nonce[0]=1; nonce[4]=(byte)0x80;
        assertArrayEquals(plain,Crypto.open(key,nonce,hex("01000000"),Arrays.copyOfRange(encrypted,5,encrypted.length)));
        nonce[4]=0;
        byte[] packet=concat(hex("c001000000"),Crypto.seal(key,nonce,hex("01000000"),hex("aabbcc")));
        byte[] corrupt=packet.clone(); corrupt[corrupt.length-1]^=1;
        assertNull("modified tag is rejected",calls.nativeOmsecAesDecrypt(session,corrupt));
        assertArrayEquals(hex("aabbcc"),calls.nativeOmsecAesDecrypt(session,packet));
        assertNull("replay is rejected",calls.nativeOmsecAesDecrypt(session,packet));
        assertEquals(88,ShadowOmsecNatives.nativeOmsecGetMaxCommandSize());
        assertEquals(231,ShadowOmsecNatives.nativeOmsecGetMaxDataSize());
        assertEquals(13,ShadowOmsecNatives.nativeOmsecGetPacketHeaderSize());
        calls.nativeOmsecShutdown(session);
        // The exact blob returned by Omron can initialize a fresh native session for reconnect.
        long reconnected=calls.nativeOmsecInitialize(fresh?state:saved);
        assertEquals(5,calls.nativeOmsecGetStartEncReq(reconnected,null)[0]);
    }

    private static Path library() {
        Path library=OmronApk.assumePresent().dir.resolve("lib/arm64-v8a/libnativeOmsec.so");
        Assume.assumeTrue("native tests need the arm64-v8a split",Files.isRegularFile(library));
        return library;
    }
    private static void configure() {
        byte[] entropy=new byte[1024]; new java.util.Random(42).nextBytes(entropy);
        ShadowOmsecNatives.configure(library(),entropy);
    }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static byte[] concat(byte[] a,byte[] b) {
        byte[] out=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,out,a.length,b.length); return out;
    }

    private static java.math.BigInteger little(byte[] data,int offset) {
        byte[] big=new byte[32];
        for(int i=0;i<32;i++) big[31-i]=data[offset+i];
        return new java.math.BigInteger(1,big);
    }
}
