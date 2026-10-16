package org.openpressure.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Per-install key encryption; values and logs never expose the LTK. */
public final class PairingKeys implements VendorPairingKeys.Backend {
    private final SharedPreferences prefs;
    public PairingKeys(Context context) { prefs=context.getSharedPreferences("pairing",Context.MODE_PRIVATE); }
    private javax.crypto.SecretKey wrappingKey() throws GeneralSecurityException {
        try {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if(store.containsAlias("openpressure-ltk")) return (javax.crypto.SecretKey)store.getKey("openpressure-ltk",null);
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("openpressure-ltk",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        } catch(java.io.IOException ex) { throw new GeneralSecurityException(ex); }
    }
    public byte[] get(String address) throws GeneralSecurityException {
        String value=prefs.getString(address,null); if(value==null) return null;
        byte[] packed=Base64.decode(value,Base64.NO_WRAP); if(packed.length<28) throw new GeneralSecurityException("Stored key damaged");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,wrappingKey(),new GCMParameterSpec(128,packed,0,12));
        cipher.updateAAD(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return cipher.doFinal(packed,12,packed.length-12);
    }
    public void put(String address,byte[] key) throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,wrappingKey());
        cipher.updateAAD(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)); byte[] encrypted=cipher.doFinal(key),iv=cipher.getIV();
        byte[] packed=new byte[iv.length+encrypted.length]; System.arraycopy(iv,0,packed,0,iv.length); System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);
        if(!prefs.edit().putString(address,Base64.encodeToString(packed,Base64.NO_WRAP)).commit()) throw new GeneralSecurityException("Could not persist pairing key");
    }
    public void forget(String address) {
        SharedPreferences.Editor edit=prefs.edit().remove(address);
        // A saved monitor owns all of its model-scoped vendor keys and provisioning candidates.
        for(String name:prefs.getAll().keySet()) if(name.startsWith(address+"/vendor/")) edit.remove(name);
        edit.apply();
    }
}
