package org.openpressure.protocol;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** AES-CMAC and the CCM parameters used by STP: 13-byte nonce, 8-byte tag. */
public final class Crypto {
    private Crypto() {}
    private static Cipher aes(byte[] key) {
        if(key==null || key.length!=16) throw new IllegalArgumentException("AES key must be 16 bytes");
        try { Cipher c=Cipher.getInstance("AES/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES")); return c; }
        catch(GeneralSecurityException ex) { throw new IllegalStateException("AES unavailable",ex); }
    }
    private static byte[] block(Cipher aes,byte[] data) {
        try { return aes.doFinal(data); } catch(GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private static byte[] xor(byte[] a,byte[] b) { byte[] out=a.clone(); for(int i=0;i<out.length;i++) out[i]^=b[i]; return out; }
    private static byte[] doubled(byte[] in) {
        byte[] out=new byte[16]; int carry=0;
        for(int i=15;i>=0;i--) { int v=in[i]&255; out[i]=(byte)((v<<1)|carry); carry=v>>>7; }
        if(carry!=0) out[15]^=(byte)0x87;
        return out;
    }
    public static byte[] cmac(byte[] key,byte[] data) {
        Cipher aes=aes(key); byte[] k1=doubled(block(aes,new byte[16])), k2=doubled(k1);
        int blocks=Math.max(1,(data.length+15)/16); byte[] state=new byte[16];
        for(int i=0;i<blocks-1;i++) state=block(aes,xor(state,Arrays.copyOfRange(data,i*16,i*16+16)));
        int offset=(blocks-1)*16, size=data.length-offset;
        byte[] last=new byte[16]; System.arraycopy(data,offset,last,0,size);
        if(size==16) last=xor(last,k1); else { last[size]=(byte)0x80; last=xor(last,k2); }
        return block(aes,xor(state,last));
    }
    public static byte[] seal(byte[] key,byte[] nonce,byte[] aad,byte[] plain) {
        validate(nonce,aad,plain.length); Cipher aes=aes(key);
        byte[] tag=mac(aes,nonce,aad,plain), s0=counter(aes,nonce,0);
        byte[] out=new byte[plain.length+8];
        crypt(aes,nonce,plain,out);
        for(int i=0;i<8;i++) out[plain.length+i]=(byte)(tag[i]^s0[i]);
        return out;
    }
    public static byte[] open(byte[] key,byte[] nonce,byte[] aad,byte[] encrypted) {
        if(encrypted.length<8) throw new IllegalArgumentException("Truncated CCM tag");
        int length=encrypted.length-8; validate(nonce,aad,length); Cipher aes=aes(key);
        byte[] plain=new byte[length]; crypt(aes,nonce,Arrays.copyOf(encrypted,length),plain);
        byte[] tag=mac(aes,nonce,aad,plain), s0=counter(aes,nonce,0), expected=new byte[8];
        for(int i=0;i<8;i++) expected[i]=(byte)(tag[i]^s0[i]);
        if(!MessageDigest.isEqual(expected,Arrays.copyOfRange(encrypted,length,encrypted.length))) {
            Arrays.fill(plain,(byte)0); throw new IllegalArgumentException("CCM authentication failed");
        }
        return plain;
    }
    private static void validate(byte[] nonce,byte[] aad,int length) {
        if(nonce.length!=13 || length>65535 || aad.length>=0xff00) throw new IllegalArgumentException("Unsupported CCM parameters");
    }
    private static byte[] counter(Cipher aes,byte[] nonce,int count) {
        byte[] input=new byte[16]; input[0]=1; System.arraycopy(nonce,0,input,1,13);
        input[14]=(byte)(count>>8); input[15]=(byte)count; return block(aes,input);
    }
    private static void crypt(Cipher aes,byte[] nonce,byte[] input,byte[] output) {
        for(int offset=0;offset<input.length;offset+=16) {
            byte[] stream=counter(aes,nonce,offset/16+1);
            for(int i=0;i<Math.min(16,input.length-offset);i++) output[offset+i]=(byte)(input[offset+i]^stream[i]);
        }
    }
    private static byte[] mac(Cipher aes,byte[] nonce,byte[] aad,byte[] plain) {
        byte[] first=new byte[16]; first[0]=(byte)(0x19|(aad.length>0?0x40:0));
        System.arraycopy(nonce,0,first,1,13); first[14]=(byte)(plain.length>>8); first[15]=(byte)plain.length;
        byte[] state=block(aes,first);
        if(aad.length>0) {
            byte[] encoded=new byte[((aad.length+2+15)/16)*16]; encoded[0]=(byte)(aad.length>>8); encoded[1]=(byte)aad.length;
            System.arraycopy(aad,0,encoded,2,aad.length); state=chain(aes,state,encoded);
        }
        return chain(aes,state,plain);
    }
    private static byte[] chain(Cipher aes,byte[] state,byte[] data) {
        for(int offset=0;offset<data.length;offset+=16) {
            byte[] part=new byte[16]; System.arraycopy(data,offset,part,0,Math.min(16,data.length-offset)); state=block(aes,xor(state,part));
        }
        return state;
    }
}
