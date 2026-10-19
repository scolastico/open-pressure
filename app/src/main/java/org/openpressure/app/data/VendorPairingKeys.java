package org.openpressure.app.data;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Recoverable provisioning: retain a durable candidate before programming a device key. */
public final class VendorPairingKeys {
    public interface Backend {
        byte[] get(String name) throws GeneralSecurityException;
        void put(String name,byte[] value) throws GeneralSecurityException;
        void forget(String name);
        /** Existence only; implementations may avoid decrypting the stored value. */
        default boolean contains(String name) throws GeneralSecurityException {
            byte[] value=get(name);
            if(value==null) return false;
            Arrays.fill(value,(byte)0); return true;
        }
    }
    private final Backend backend;
    private final String current,pending;
    public VendorPairingKeys(Backend backend,String address,String model) {
        if(backend==null || address==null || address.isEmpty() || model==null || !model.matches("HEM-[A-Z0-9]+(?:-[A-Z0-9]+)*"))
            throw new IllegalArgumentException("Missing vendor key identity");
        this.backend=backend; current=address+"/vendor/"+model; pending=current+"/pending";
    }
    private static void check(byte[] key) {
        if(key==null || key.length!=16) throw new IllegalArgumentException("Vendor key must contain 16 bytes");
    }
    public void stage(byte[] key) throws GeneralSecurityException {
        check(key);
        byte[] existing=backend.get(pending);
        try {
            if(existing!=null && !Arrays.equals(existing,key))
                throw new IllegalStateException("An unacknowledged provisioning key must be retained");
            backend.put(pending,key);
        } finally { if(existing!=null) Arrays.fill(existing,(byte)0); }
    }
    /** A Pair retry reuses the possibly programmed candidate instead of destroying it. */
    public byte[] provisioningKey() throws GeneralSecurityException {
        byte[] staged=backend.get(pending);
        if(staged!=null) { check(staged); return staged; }
        byte[] fresh=new byte[16]; new java.security.SecureRandom().nextBytes(fresh);
        try { stage(fresh); return fresh; }
        catch(GeneralSecurityException | RuntimeException failure) { Arrays.fill(fresh,(byte)0); throw failure; }
    }
    public void confirm(byte[] key) throws GeneralSecurityException {
        check(key); backend.put(current,key); backend.forget(pending);
    }
    /** Whether Connect has any saved or pending candidate, without exposing key material. */
    public boolean hasCandidate() throws GeneralSecurityException {
        return backend.contains(pending) || backend.contains(current);
    }
    /** Pending first handles a disconnect after programming but before its acknowledgment. */
    public List<byte[]> candidates() throws GeneralSecurityException {
        List<byte[]> result=new ArrayList<>();
        byte[] staged=backend.get(pending),saved=backend.get(current);
        if(staged!=null) { check(staged); result.add(staged); }
        if(saved!=null) {
            check(saved);
            if(staged==null || !Arrays.equals(staged,saved)) result.add(saved);
            else Arrays.fill(saved,(byte)0);
        }
        return result;
    }
}
