package org.openpressure.protocol;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Single-threaded ATT queue. Owner supplies scheduling and callback identity. */
public final class OperationQueue {
    private static final class Operation {
        final String key;
        final BooleanSupplier start;
        final Runnable success;
        final Consumer<String> recoverableFailure;
        Operation(String key,BooleanSupplier start,Runnable success,Consumer<String> recoverableFailure) { this.key=key; this.start=start; this.success=success; this.recoverableFailure=recoverableFailure; }
    }
    private final ArrayDeque<Operation> waiting=new ArrayDeque<>();
    private final Consumer<String> failure;
    private Operation active;
    public OperationQueue(Consumer<String> failure) { this.failure=failure; }
    public void add(String key,BooleanSupplier start,Runnable success) {
        add(key,start,success,null);
    }
    public void add(String key,BooleanSupplier start,Runnable success,Consumer<String> recoverableFailure) {
        waiting.add(new Operation(key,start,success,recoverableFailure)); runNext();
    }
    public boolean busy() { return active!=null; }
    public String activeKey() { return active==null ? null : active.key; }
    /** Route timeouts through the same required/optional failure policy as ATT errors. */
    public void timeout(String key) {
        if(active!=null && active.key.equals(key)) fail("Bluetooth operation timed out: ");
    }
    public void complete(String key,int status) {
        if (active==null || !active.key.equals(key)) return;
        Operation done=active; active=null;
        if (status!=0) {
            String message=done.key + " failed (" + status + ")";
            if(done.recoverableFailure!=null) { done.recoverableFailure.accept(message); runNext(); }
            else { waiting.clear(); failure.accept(message); }
            return;
        }
        done.success.run(); runNext();
    }
    /**
     * Starts the still-active operation once more, for an owner that held back its failed completion (for example
     * until a link-security bond completed). False when another or no operation is active; a rejected start fails
     * like any other start.
     */
    public boolean restart(String key) {
        if(active==null || !active.key.equals(key)) return false;
        try {
            if(!active.start.getAsBoolean()) fail("Android rejected ");
        } catch(RuntimeException ex) { fail("Could not start: " + ex.getMessage() + ": "); }
        return true;
    }
    public void clear() { waiting.clear(); active=null; }
    private void runNext() {
        if (active!=null || waiting.isEmpty()) return;
        active=waiting.remove();
        try {
            if (!active.start.getAsBoolean()) fail("Android rejected ");
        } catch (RuntimeException ex) { fail("Could not start: " + ex.getMessage() + ": "); }
    }
    private void fail(String prefix) {
        Operation done=active; active=null;
        if(done.recoverableFailure!=null) { done.recoverableFailure.accept(prefix+done.key); runNext(); }
        else { clear(); failure.accept(prefix+done.key); }
    }
}
