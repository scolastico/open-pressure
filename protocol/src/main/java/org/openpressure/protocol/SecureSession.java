package org.openpressure.protocol;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/** Independent implementation of the statically recovered STP wire protocol. No native code. */
public final class SecureSession implements AutoCloseable {
    private enum State { NEW, PAIR, ENCRYPTION, CHALLENGE, READY, CLOSED }
    private State state=State.NEW;
    private final SecureRandom random;
    private KeyPair ephemeral;
    private byte[] ltk, address, nonce, peerAddress, peerNonce, shared, challenge, skd, iv, nonceTail, sessionKey;
    private long sent, received;
    public SecureSession(byte[] savedKey) { this(savedKey,new SecureRandom()); }
    SecureSession(byte[] savedKey,SecureRandom random) {
        if(savedKey!=null && savedKey.length!=16) throw new IllegalArgumentException("Invalid LTK length");
        ltk=savedKey==null ? null : savedKey.clone(); this.random=random;
    }
    public boolean established() { return state==State.READY; }
    public byte[] begin() {
        if(state!=State.NEW) throw new IllegalArgumentException("Session already started");
        if(ltk!=null) return startEncryption();
        try {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"),random); ephemeral=generator.generateKeyPair();
            address=random(7); nonce=random(16);
            ECPublicKey pub=(ECPublicKey)ephemeral.getPublic();
            byte[] request=concat(new byte[]{1},address,nonce,littleCoordinate(pub.getW().getAffineX()),littleCoordinate(pub.getW().getAffineY()));
            state=State.PAIR; return wrap(request);
        } catch(GeneralSecurityException ex) { close(); throw new IllegalArgumentException("P-256 unavailable",ex); }
    }
    public byte[] accept(byte[] notification) {
        try {
            if(notification==null || notification.length<2) throw new IllegalArgumentException("Truncated pairing response");
            if((notification[0]&255)==0xff) throw new IllegalArgumentException("Pairing rejected: " + (notification[1]&255));
            if((notification[0]&255)!=0xf0) throw new IllegalArgumentException("Unexpected pairing response prefix");
            byte[] body=Arrays.copyOfRange(notification,1,notification.length);
            if(state==State.PAIR) {
                require(body,0x81,88);
                peerAddress=Arrays.copyOfRange(body,1,8); peerNonce=Arrays.copyOfRange(body,8,24);
                ECPublicKey local=(ECPublicKey)ephemeral.getPublic();
                ECPoint point=new ECPoint(bigCoordinate(body,24),bigCoordinate(body,56));
                PublicKey peer=KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point,local.getParams()));
                KeyAgreement agreement=KeyAgreement.getInstance("ECDH"); agreement.init(ephemeral.getPrivate()); agreement.doPhase(peer,true);
                shared=agreement.generateSecret(); ephemeral=null;
                return startEncryption();
            }
            if(state==State.ENCRYPTION) {
                require(body,0x85,45);
                if(ltk==null) {
                    byte[] salt={(byte)0x6c,(byte)0x88,(byte)0x83,(byte)0x91,(byte)0xaa,(byte)0xf5,(byte)0xa5,(byte)0x38,(byte)0x60,(byte)0x37,(byte)0x0b,(byte)0xdb,(byte)0x5a,(byte)0x60,(byte)0x83,(byte)0xbe};
                    byte[] intermediate=Crypto.cmac(salt,shared);
                    ltk=Crypto.cmac(intermediate,concat(new byte[]{1,'b','t','l','e'},nonce,peerNonce,address,peerAddress,new byte[]{1,0}));
                    wipe(intermediate); wipe(shared); shared=null;
                }
                sessionKey=Crypto.cmac(ltk,concat(Arrays.copyOfRange(body,17,25),skd));
                nonceTail=concat(Arrays.copyOfRange(body,25,29),iv);
                byte[] plain=concat(Arrays.copyOfRange(body,1,17),new byte[16],random(4));
                state=State.CHALLENGE;
                return wrap(concat(new byte[]{6},Crypto.seal(sessionKey,ccmNonce(0,true),new byte[4],plain)));
            }
            if(state==State.CHALLENGE) {
                require(body,0x86,45);
                byte[] plain=Crypto.open(sessionKey,ccmNonce(0,false),new byte[4],Arrays.copyOfRange(body,1,45));
                boolean matches=MessageDigest.isEqual(challenge,Arrays.copyOf(plain,16)); wipe(plain);
                if(!matches) throw new IllegalArgumentException("Peer challenge did not match");
                wipe(challenge); state=State.READY; return null;
            }
            throw new IllegalArgumentException("Unexpected handshake state");
        } catch(GeneralSecurityException | IllegalArgumentException ex) { close(); throw new IllegalArgumentException("Secure pairing failed: " + ex.getMessage(),ex); }
    }
    private byte[] startEncryption() {
        challenge=random(16); skd=random(8); iv=random(4); state=State.ENCRYPTION;
        return wrap(concat(new byte[]{5},challenge,skd,iv,new byte[16]));
    }
    public byte[] longTermKey() { ready(); return ltk.clone(); }
    public byte[] encrypt(byte[] plain) {
        ready(); if(plain.length>231 || sent==0xffffffffL) throw new IllegalArgumentException("STP packet limit reached");
        long count=++sent; byte[] aad=counter(count);
        return concat(new byte[]{(byte)0xc0},aad,Crypto.seal(sessionKey,ccmNonce(count,true),aad,plain));
    }
    public byte[] decrypt(byte[] packet) {
        ready(); if(packet.length<13 || packet.length>244 || (packet[0]&255)!=0xc0) throw new IllegalArgumentException("Invalid encrypted packet");
        long count=0; for(int i=4;i>=1;i--) count=(count<<8)|(packet[i]&255L);
        if(count<=received) throw new IllegalArgumentException("STP replay rejected");
        byte[] plain=Crypto.open(sessionKey,ccmNonce(count,false),Arrays.copyOfRange(packet,1,5),Arrays.copyOfRange(packet,5,packet.length));
        received=count; return plain;
    }
    private byte[] ccmNonce(long count,boolean outgoing) { return concat(counter(count),new byte[]{outgoing?(byte)0x80:0},nonceTail); }
    private static byte[] counter(long count) { return new byte[]{(byte)count,(byte)(count>>8),(byte)(count>>16),(byte)(count>>24)}; }
    private void ready() { if(state!=State.READY) throw new IllegalArgumentException("Secure session not established"); }
    private byte[] random(int size) { byte[] bytes=new byte[size]; random.nextBytes(bytes); return bytes; }
    private static void require(byte[] data,int opcode,int size) { if(data.length!=size || (data[0]&255)!=opcode) throw new IllegalArgumentException("Unexpected STP response"); }
    private static byte[] wrap(byte[] data) { return concat(new byte[]{0x70},data); }
    private static byte[] littleCoordinate(BigInteger value) {
        byte[] big=value.toByteArray(), out=new byte[32];
        for(int i=0;i<32 && i<big.length;i++) out[i]=big[big.length-1-i]; return out;
    }
    private static BigInteger bigCoordinate(byte[] bytes,int offset) {
        byte[] big=new byte[32]; for(int i=0;i<32;i++) big[31-i]=bytes[offset+i]; return new BigInteger(1,big);
    }
    private static byte[] concat(byte[]... parts) {
        int size=0; for(byte[] p:parts) size+=p.length;
        byte[] out=new byte[size]; int offset=0;
        for(byte[] p:parts) { System.arraycopy(p,0,out,offset,p.length); offset+=p.length; } return out;
    }
    private static void wipe(byte[] value) { if(value!=null) Arrays.fill(value,(byte)0); }
    @Override public void close() {
        for(byte[] secret:new byte[][]{ltk,shared,sessionKey,challenge,nonce,peerNonce,skd,iv,nonceTail,address,peerAddress}) wipe(secret);
        ephemeral=null; state=State.CLOSED;
    }
}
