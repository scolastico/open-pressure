package org.openpressure.protocol.fixture;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.KeyAgreement;
import org.openpressure.protocol.Crypto;

/**
 * The cuff's side of the X7 application encryption (STP, Omron's WLP1COM_PAIR), for tests and fixture replays only.
 * It answers a fresh application pairing ({@code 70 01} → {@code F0 81}, {@code 70 05} → {@code F0 85},
 * {@code 70 06} → {@code F0 86}) or a saved-key authentication ({@code 70 05} / {@code 70 06} only), then opens the
 * phone's {@code C0 <counter> <ciphertext + tag>} frames and seals the cuff's replies under its own counter. The
 * format follows docs/protocol.md; it is written independently of {@code SecureSession} so that the two can disagree.
 *
 * <p>All cuff material is fixed and synthetic: a P-256 private scalar (the ASCII bytes of
 * "OpenPressure synthetic cuff key!"), a 7-byte address of {@code A1}, a pairing nonce of {@code B2}, challenge
 * {@code 07}, session-key diversifier {@code 08}, IV {@code 09} and the four trailing challenge-reply bytes {@code 0A}.
 * The phone's counter must rise by exactly one per frame across all characteristics; anything else is refused.
 */
public final class SecurePeer {
    private enum State { NEW, PAIRED, ENCRYPTION, READY }
    private static final byte[] SALT=hex("6c888391aaf5a53860370bdb5a6083be");
    private static final BigInteger PRIVATE=new BigInteger("4f70656e50726573737572652073796e7468657469632063756666206b657921",16);
    /** The public key for {@link #PRIVATE}, big-endian affine coordinates. */
    private static final BigInteger PUBLIC_X=new BigInteger("fcdb0d9db093447e0df3e1ecef729f9dfc2e5de5a132a8928dbb2b96921fcedc",16),
        PUBLIC_Y=new BigInteger("73ceec60723e21f6747bbcacd07666bb29365ce5e7fbf9fe9edc4782459d39c6",16);
    private static final byte[] ADDRESS=filled(7,0xa1),NONCE=filled(16,0xb2),CHALLENGE=filled(16,0x07),SKD=filled(8,0x08),IV=filled(4,0x09),TAIL=filled(4,0x0a);

    private final byte[] reserved;
    private State state=State.NEW;
    private byte[] ltk,sessionKey,nonceTail,phoneChallenge;
    private long phoneCounter,cuffCounter;

    /** A cuff that knows {@code savedLtk} (16 bytes) from an earlier pairing, or none ({@code null}); reserved {@code F0 85} bytes are zero. */
    public SecurePeer(byte[] savedLtk) { this(savedLtk,new byte[16]); }
    /** As {@link #SecurePeer(byte[])}, with the 16 reserved bytes the cuff sends at the end of its {@code F0 85} reply. */
    public SecurePeer(byte[] savedLtk,byte[] reserved) {
        if(savedLtk!=null && savedLtk.length!=16) throw new IllegalArgumentException("LTK must be 16 bytes");
        if(reserved==null || reserved.length!=16) throw new IllegalArgumentException("reserved bytes must be 16 bytes");
        ltk=savedLtk==null?null:savedLtk.clone();
        this.reserved=reserved.clone();
    }

    public boolean established() { return state==State.READY; }
    /** The application key: the saved one, or the one derived by a fresh pairing; null before either exists. */
    public byte[] ltk() { return ltk==null?null:ltk.clone(); }
    /** The last accepted phone counter (0 before the first encrypted frame). */
    public long phoneCounter() { return phoneCounter; }
    /** The counter of the last frame the cuff sealed (0 before the first). */
    public long cuffCounter() { return cuffCounter; }

    /** The cuff's reply ({@code F0 81/85/86 …}) to one phone handshake frame; refuses anything out of order or malformed. */
    public byte[] handshake(byte[] request) {
        if(request==null || request.length<2 || request[0]!=0x70) throw new IllegalArgumentException("not a handshake frame");
        int opcode=request[1]&255;
        if(opcode==1) return pair(request);
        if(opcode==5) return startEncryption(request);
        if(opcode==6) return challenge(request);
        throw new IllegalArgumentException("unknown handshake opcode "+opcode);
    }
    private byte[] pair(byte[] request) {
        if(state!=State.NEW) throw new IllegalArgumentException("70 01 after the pairing started");
        if(request.length!=89) throw new IllegalArgumentException("70 01 has "+request.length+" bytes, expected 89");
        byte[] shared;
        try {
            ECParameterSpec curve=curve();
            KeyFactory keys=KeyFactory.getInstance("EC");
            PublicKey phone=keys.generatePublic(new ECPublicKeySpec(new ECPoint(little(request,25),little(request,57)),curve));
            PrivateKey cuff=keys.generatePrivate(new ECPrivateKeySpec(PRIVATE,curve));
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");
            agreement.init(cuff); agreement.doPhase(phone,true);
            shared=agreement.generateSecret();
        } catch(GeneralSecurityException invalid) { throw new IllegalArgumentException("phone public key refused: "+invalid.getMessage(),invalid); }
        // LTK = CMAC(CMAC(SALT, W), 01 "btle" Nphone Ndevice Aphone Adevice 01 00)
        ltk=Crypto.cmac(Crypto.cmac(SALT,shared),concat(new byte[]{1,'b','t','l','e'},Arrays.copyOfRange(request,9,25),NONCE,
            Arrays.copyOfRange(request,2,9),ADDRESS,new byte[]{1,0}));
        state=State.PAIRED;
        return concat(new byte[]{(byte)0xf0,(byte)0x81},ADDRESS,NONCE,little(PUBLIC_X),little(PUBLIC_Y));
    }
    private byte[] startEncryption(byte[] request) {
        if(state!=State.NEW && state!=State.PAIRED || ltk==null) throw new IllegalArgumentException("70 05 without a saved or freshly paired key");
        if(request.length!=46) throw new IllegalArgumentException("70 05 has "+request.length+" bytes, expected 46");
        for(int i=30;i<46;i++) if(request[i]!=0) throw new IllegalArgumentException("70 05 reserved bytes 30-45 are not zero: "+HexFormat.of().formatHex(request,30,46));
        phoneChallenge=Arrays.copyOfRange(request,2,18);
        // SK = CMAC(LTK, SKDdevice || SKDphone); the CCM nonce ends with IVdevice || IVphone.
        sessionKey=Crypto.cmac(ltk,concat(SKD,Arrays.copyOfRange(request,18,26)));
        nonceTail=concat(IV,Arrays.copyOfRange(request,26,30));
        state=State.ENCRYPTION;
        return concat(new byte[]{(byte)0xf0,(byte)0x85},CHALLENGE,SKD,IV,reserved);
    }
    private byte[] challenge(byte[] request) {
        if(state!=State.ENCRYPTION) throw new IllegalArgumentException("70 06 before 70 05");
        if(request.length!=46) throw new IllegalArgumentException("70 06 has "+request.length+" bytes, expected 46");
        byte[] opened;
        try { opened=Crypto.open(sessionKey,nonce(0,true),new byte[4],Arrays.copyOfRange(request,2,46)); }
        catch(IllegalArgumentException tag) { throw new IllegalArgumentException("70 06 does not authenticate under the derived session key",tag); }
        if(!Arrays.equals(CHALLENGE,Arrays.copyOf(opened,16))) throw new IllegalArgumentException("70 06 answers the wrong challenge");
        for(int i=16;i<32;i++) if(opened[i]!=0) throw new IllegalArgumentException("70 06 plaintext bytes 16-31 are not zero: "+HexFormat.of().formatHex(opened,16,32));
        state=State.READY;
        return concat(new byte[]{(byte)0xf0,(byte)0x86},Crypto.seal(sessionKey,nonce(0,false),new byte[4],concat(phoneChallenge,new byte[16],TAIL)));
    }

    /** Opens one phone frame; its counter must be exactly one more than the previous frame's. */
    public byte[] decrypt(byte[] packet) {
        if(state!=State.READY) throw new IllegalArgumentException("encrypted frame before the handshake completed");
        if(packet==null || packet.length<13 || (packet[0]&255)!=0xc0) throw new IllegalArgumentException("not a C0-framed encrypted packet");
        long count=0;
        for(int i=4;i>=1;i--) count=(count<<8)|(packet[i]&255L);
        if(count!=phoneCounter+1) throw new IllegalArgumentException("phone counter "+count+" after "+phoneCounter+", expected "+(phoneCounter+1));
        byte[] plain;
        try { plain=Crypto.open(sessionKey,nonce(count,true),Arrays.copyOfRange(packet,1,5),Arrays.copyOfRange(packet,5,packet.length)); }
        catch(IllegalArgumentException tag) { throw new IllegalArgumentException("frame "+count+" does not authenticate",tag); }
        phoneCounter=count;
        return plain;
    }
    /** Seals one cuff frame under the next cuff counter (the first is 1). */
    public byte[] encrypt(byte[] plain) {
        if(state!=State.READY) throw new IllegalArgumentException("encrypted frame before the handshake completed");
        long count=++cuffCounter;
        byte[] aad=counter(count);
        return concat(new byte[]{(byte)0xc0},aad,Crypto.seal(sessionKey,nonce(count,false),aad,plain));
    }

    /** CCM nonce: counter (4 bytes LE), direction (80 from the phone, 00 from the cuff), IVdevice, IVphone. */
    private byte[] nonce(long count,boolean fromPhone) { return concat(counter(count),new byte[]{(byte)(fromPhone?0x80:0)},nonceTail); }
    private static byte[] counter(long count) { return new byte[]{(byte)count,(byte)(count>>8),(byte)(count>>16),(byte)(count>>24)}; }
    private static ECParameterSpec curve() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return ((ECPublicKey)generator.generateKeyPair().getPublic()).getParams();
    }
    private static BigInteger little(byte[] bytes,int offset) {
        byte[] big=new byte[32];
        for(int i=0;i<32;i++) big[31-i]=bytes[offset+i];
        return new BigInteger(1,big);
    }
    private static byte[] little(BigInteger value) {
        byte[] big=value.toByteArray(),out=new byte[32];
        for(int i=0;i<32 && i<big.length;i++) out[i]=big[big.length-1-i];
        return out;
    }
    private static byte[] filled(int size,int value) { byte[] out=new byte[size]; Arrays.fill(out,(byte)value); return out; }
    private static byte[] concat(byte[]... parts) {
        int size=0;
        for(byte[] p:parts) size+=p.length;
        byte[] out=new byte[size];
        int offset=0;
        for(byte[] p:parts) { System.arraycopy(p,0,out,offset,p.length); offset+=p.length; }
        return out;
    }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
}
