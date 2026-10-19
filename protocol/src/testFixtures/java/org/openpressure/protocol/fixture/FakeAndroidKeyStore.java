package org.openpressure.protocol.fixture;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * In-memory stand-in for Android's "AndroidKeyStore" JCA provider in JVM tests. Secret keys only; generated
 * keys are stored under the alias of the Android KeyGenParameterSpec (read reflectively, no Android dependency).
 */
public final class FakeAndroidKeyStore extends Provider {
    private static final Map<String,Key> KEYS=new ConcurrentHashMap<>();

    private FakeAndroidKeyStore() {
        super("AndroidKeyStore","1.0","In-memory AndroidKeyStore for tests");
        put("KeyStore.AndroidKeyStore",Store.class.getName());
        put("KeyGenerator.AES",Generator.class.getName());
        put("KeyGenerator.HmacSHA256",Generator.class.getName());
    }
    /** Installs the provider once and clears previously stored keys. */
    public static synchronized void install() {
        KEYS.clear();
        if(Security.getProvider("AndroidKeyStore")==null) Security.addProvider(new FakeAndroidKeyStore());
    }

    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias,char[] password) { return KEYS.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return KEYS.containsKey(alias)?new Date(0):null; }
        @Override public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] chain) { KEYS.put(alias,key); }
        @Override public void engineSetKeyEntry(String alias,byte[] key,Certificate[] chain) { throw new UnsupportedOperationException(); }
        @Override public void engineSetCertificateEntry(String alias,Certificate cert) { throw new UnsupportedOperationException(); }
        @Override public void engineDeleteEntry(String alias) { KEYS.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return KEYS.containsKey(alias); }
        @Override public int engineSize() { return KEYS.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return KEYS.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(OutputStream stream,char[] password) {}
        @Override public void engineLoad(InputStream stream,char[] password) {}
    }

    public static final class Generator extends KeyGeneratorSpi {
        private String alias;
        private int bits=256;
        @Override protected void engineInit(SecureRandom random) {}
        @Override protected void engineInit(int keySize,SecureRandom random) { bits=keySize; }
        @Override protected void engineInit(AlgorithmParameterSpec spec,SecureRandom random) {
            try {
                alias=(String)spec.getClass().getMethod("getKeystoreAlias").invoke(spec);
                Object size=spec.getClass().getMethod("getKeySize").invoke(spec);
                if(size instanceof Integer && (Integer)size>0) bits=(Integer)size;
            } catch(ReflectiveOperationException unsupported) { throw new IllegalArgumentException("Unsupported key spec "+spec,unsupported); }
        }
        @Override protected SecretKey engineGenerateKey() {
            // Deterministic material keeps sandbox transcripts reproducible; it never protects real data.
            byte[] material=new byte[bits/8];
            for(int i=0;i<material.length;i++) material[i]=(byte)(i*31+7);
            SecretKey key=new SecretKeySpec(material,"AES");
            if(alias!=null) KEYS.put(alias,key);
            return key;
        }
    }
}
