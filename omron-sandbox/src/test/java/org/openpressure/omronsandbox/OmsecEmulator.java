package org.openpressure.omronsandbox;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.VM;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Runs Omron's JNI exports in an ARM64 VM. Only its entropy source is substituted. */
final class OmsecEmulator implements AutoCloseable {
    private final AndroidEmulator emulator;
    private final DvmClass type;
    private final DvmObject<?> receiver;
    private final Set<Long> sessions=new HashSet<>();
    private final byte[] entropy;
    private final java.util.Random fallback=new java.util.Random(0);
    private int consumed;
    private IllegalStateException entropyFailure;
    private boolean closed;

    OmsecEmulator(Path library,byte[] random) {
        if(!Files.isRegularFile(library)) throw new IllegalStateException("Omsec ARM64 library missing; supply the arm64-v8a split in omron-sandbox/apk/ and rerun omronExtract (docs/omron-sandbox.md)");
        entropy=random.clone();
        emulator=AndroidEmulatorBuilder.for64Bit().addBackendFactory(new Unicorn2Factory(false)).build();
        try {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            VM vm=emulator.createDalvikVM();
            Module module=vm.loadLibrary(library.toFile(),false).getModule();
            // Both key generation and protocol nonces use micro-ecc's registered RNG. Keep all crypto native.
            module.callFunction(emulator,"uECC_set_rng",emulator.getSvcMemory().registerSvc(new Arm64Svc("fixture entropy") {
                @Override public long handle(Emulator<?> emu) {
                    int size=emu.getContext().getIntArg(1);
                    byte[] bytes=new byte[size];
                    if(entropy.length==0) fallback.nextBytes(bytes);
                    else {
                        if(size>entropy.length-consumed) {
                            entropyFailure=new IllegalStateException("Omsec fixture random exhausted after "+consumed+" bytes (requested "+size+")");
                            return 0;
                        }
                        System.arraycopy(entropy,consumed,bytes,0,size);
                        consumed+=size;
                    }
                    emu.getContext().getPointerArg(0).write(0,bytes,0,size);
                    Arrays.fill(bytes,(byte)0);
                    return 1;
                }
            }));
            type=vm.resolveClass(OmronAnchors.LIB.replace('.','/')+"/ohq/OHQCustomPairing");
            receiver=type.newObject(null);
        } catch(RuntimeException | Error failure) {
            try { emulator.close(); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    synchronized long initialize(byte[] savedState) {
        open();
        long session=receiver.callJniMethodLong(emulator,"nativeOmsecInitialize([B)J",(Object)savedState);
        checkEntropy();
        if(session!=0) sessions.add(session);
        return session;
    }

    synchronized int size(String method) {
        open();
        return type.callStaticJniMethodInt(emulator,method+"()I");
    }

    synchronized byte[] bytes(String method,long session,byte[] data) {
        valid(session);
        DvmObject<byte[]> result=method.equals("nativeOmsecGetPairingReq")
            ?receiver.callJniMethodObject(emulator,method+"(J)[B",session)
            :receiver.callJniMethodObject(emulator,method+"(J[B)[B",session,data);
        checkEntropy();
        return result==null?null:result.getValue().clone();
    }

    synchronized void shutdown(long session) {
        valid(session);
        receiver.callJniMethod(emulator,"nativeOmsecShutdown(J)V",session);
        sessions.remove(session);
    }

    private void checkEntropy() { if(entropyFailure!=null) throw entropyFailure; }
    private void open() { if(closed) throw new IllegalStateException("Omsec emulator closed"); }
    private void valid(long session) {
        open();
        if(!sessions.contains(session)) throw new IllegalStateException("Unknown or closed Omsec session");
    }

    @Override public synchronized void close() {
        if(closed) return;
        try {
            for(long session:new HashSet<>(sessions)) shutdown(session);
        } finally {
            closed=true;
            Arrays.fill(entropy,(byte)0);
            try { emulator.close(); } catch(IOException failure) { throw new IllegalStateException("Cannot close Omsec emulator",failure); }
        }
    }
}
