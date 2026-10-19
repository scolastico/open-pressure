package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;

/** Reject everything except the exact control messages used by this BP client. */
public final class DeviceWritePolicy {
    private static UUID sig(String shortUuid) { return UUID.fromString("0000"+shortUuid+"-0000-1000-8000-00805f9b34fb"); }
    private static final UUID BP=sig("1810"), MEASUREMENT=sig("2a35"), RACP=sig("2a52"),
        UDS=sig("181c"), UCP=sig("2a9f"), CTS=sig("1805"), CURRENT_TIME=sig("2a2b"),
        COMMAND=sig("5045"), DATE_TIME=sig("2a08"), CCCD=sig("2902"), ALIAS=sig("fe4a"),
        VENDOR=UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"),
        OPTIONAL=UUID.fromString("5df5e817-a945-4f81-89c0-3d4e9759c07c"),
        HANDSHAKE=UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b"),
        WLP_TX=UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
        WLP_RX=UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b");
    private static final Set<UUID> WLP_CHANNELS=new HashSet<>(Arrays.asList(
        UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
        UUID.fromString("e0b8a060-aee7-11e1-92f4-0002a5d5c51b"),
        UUID.fromString("0ae12b00-aee8-11e1-a192-0002a5d5c51b"),
        UUID.fromString("10e1ba60-aee8-11e1-89e5-0002a5d5c51b"),
        UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b"),
        UUID.fromString("4d0bf320-aee8-11e1-a0d9-0002a5d5c51b"),
        UUID.fromString("5128ce60-aee8-11e1-b84b-0002a5d5c51b"),
        UUID.fromString("560f1420-aee8-11e1-8184-0002a5d5c51b"),
        UUID.fromString("8858eb40-aee8-11e1-bb67-0002a5d5c51b")));
    private DeviceWritePolicy() {}
    public static boolean isWlpDataCharacteristic(UUID characteristic) { return WLP_CHANNELS.contains(characteristic); }
    private static void requireProfile(ProtocolProfile profile) {
        if(profile!=ProtocolProfile.STANDARD_BP && profile!=ProtocolProfile.STP) reject();
    }
    private static boolean vendor(UUID service) { return VENDOR.equals(service)||ALIAS.equals(service); }
    private static boolean history(UUID service,UUID characteristic) { return (OPTIONAL.equals(service)||BP.equals(service)) && RACP.equals(characteristic); }
    /**
     * The registration addressed-write exception: an explicitly authorized, identity-bound registration frame.
     * The write-aspect gate runs before the plan's live permit is consulted.
     */
    public static void validateX7Registration(ProtocolProfile profile,UUID service,UUID characteristic,byte[] payload,X7Registration registration) {
        if(profile!=ProtocolProfile.X7_WLP || !ALIAS.equals(service) || !WLP_TX.equals(characteristic) || registration==null) reject();
        requireWriteAspect(registration.identity(),WriteAspectIndex.Aspect.REGISTRATION,registration.override());
        if(!registration.allows(payload)) reject();
    }
    /**
     * The authorized clock-block write of a vendor-memory model: one chunk of the plan's frame on
     * its own TX channel. The single-use permit is consumed last, after every other check passed.
     */
    public static void validateVendorClockBlock(VendorMemoryModel model,UUID service,UUID characteristic,byte[] payload,VendorClockWrite plan) {
        VendorWlpSession.requireModel(model);
        if(plan==null || payload==null || !VendorWlpSession.service(model).equals(service)) reject();
        int channel=VendorWlpSession.txChannels(model).indexOf(characteristic);
        if(channel<0) reject();
        requireWriteAspect(plan.identity(),WriteAspectIndex.Aspect.CLOCK_BLOCK,plan.override());
        if(!model.id.equals(plan.identity()) || !plan.allowsChunk(channel,payload)) reject();
    }
    /**
     * The authorized X7 clock-block write: the plan's single plaintext frame on the WLP TX channel. Plain WLP carries a
     * plain plan; an established encrypted session carries only a plan built for the encrypted transport, and the
     * caller encrypts the frame after this check.
     */
    public static void validateX7ClockBlock(ProtocolProfile profile,UUID service,UUID characteristic,byte[] payload,VendorClockWrite plan) {
        if(!ALIAS.equals(service) || !WLP_TX.equals(characteristic) || payload==null || plan==null) reject();
        if(!(profile==ProtocolProfile.X7_WLP && !plan.encryptedTransport() || profile==ProtocolProfile.X7_SECURE_WLP && plan.encryptedTransport())) reject();
        requireWriteAspect(plan.identity(),WriteAspectIndex.Aspect.CLOCK_BLOCK,plan.override());
        // A vendor-memory plan's legacy chunk never rides the X7 transport.
        if(VendorMemoryModel.identify(plan.identity())!=null || !plan.allowsChunk(0,payload)) reject();
    }
    /** Vendor reads require a positively identified model; generic profile selection grants no access. */
    public static void validateVendorCharacteristic(VendorMemoryModel model,UUID service,UUID characteristic,byte[] payload) {
        VendorWlpSession.requireModel(model);
        if(!VendorWlpSession.service(model).equals(service) || payload==null) reject();
        if(VendorWlpSession.unlockCharacteristic().equals(characteristic)) reject();
        if(!VendorWlpSession.txChannels(model).get(0).equals(characteristic) || payload.length!=8 || payload[0]!=8 || payload[6]!=0) reject();
        byte bcc=0; for(byte b:payload) bcc^=b; if(bcc!=0) reject();
        if(Arrays.equals(payload,new byte[]{8,0,0,0,0,16,0,24}) || Arrays.equals(payload,new byte[]{8,15,0,0,0,0,0,7})) return;
        if(payload[1]!=1 || payload[2]!=0) reject();
        int address=((payload[3]&255)<<8)|(payload[4]&255),length=payload[5]&255;
        if(length<1 || length>VendorWlpSession.maxBlock(model)) reject();
        if(containsRange(model.settingsStart,model.settingsLength,address,length)) return;
        for(VendorMemoryModel.Range range:model.historyRanges()) if(containsRange(range.start,range.length,address,length)) return;
        reject();
    }
    /** Key programming and reconnect have disjoint, short-lived command permits. */
    public static void validateVendorUnlock(VendorMemoryModel model,UUID service,UUID characteristic,byte[] payload,LegacyUnlock permit) {
        VendorWlpSession.requireModel(model);
        if(!model.legacyUnlock || !VendorWlpSession.service(model).equals(service) ||
                !VendorWlpSession.unlockCharacteristic().equals(characteristic) || permit==null || !permit.allows(payload)) reject();
    }
    public static void validateVendorDescriptor(VendorMemoryModel model,UUID service,UUID characteristic,UUID descriptor,byte[] payload) {
        VendorWlpSession.requireModel(model);
        if(!VendorWlpSession.service(model).equals(service) || !CCCD.equals(descriptor) || !Arrays.equals(payload,new byte[]{1,0})) reject();
        if(VendorWlpSession.rxChannels(model).contains(characteristic) || model.legacyUnlock && VendorWlpSession.unlockCharacteristic().equals(characteristic)) return;
        reject();
    }
    private static boolean containsRange(int start,int size,int address,int length) {
        return address>=start && address+length<=start+size;
    }
    /** Single-channel catalog reads are bound to the DIS model, in plaintext before any encryption. */
    public static void validateX7Characteristic(ProtocolProfile profile,X7Model model,UUID service,UUID characteristic,byte[] payload) {
        if(profile==null || !profile.isX7() || model==null) reject();
        validateCharacteristic(profile,service,characteristic,payload);
        if(model.restrictedReads && WLP_TX.equals(characteristic) && payload.length==8 && payload[1]==1) {
            int address=((payload[3]&255)<<8)|(payload[4]&255),length=payload[5]&255;
            if(!model.containsReadRange(address,length)) reject();
        }
    }
    public static void validateCharacteristic(ProtocolProfile profile,UUID service,UUID characteristic,byte[] payload) {
        if(profile!=null && profile.isX7()) {
            if(!ALIAS.equals(service) || payload==null) reject();
            if(HANDSHAKE.equals(characteristic)) {
                if(profile==ProtocolProfile.X7_SECURE_WLP) {
                    if(isSecureHandshake(payload)) return;
                    if(payload.length==20 && payload[0]==0x20 && payload[1]==1) {
                        for(int i=2;i<20;i++) if(payload[i]!=0) reject();
                        return;
                    }
                }
                // Settings and WLP4COM registration commands remain blocked in both X7 modes.
                if(payload.length!=20 || payload[0]!=0x11) reject();
                for(int i=5;i<20;i++) if(payload[i]!=0) reject();
                return;
            }
            if(!WLP_TX.equals(characteristic) || payload.length!=8 || payload[0]!=8 || payload[6]!=0) reject();
            byte bcc=0; for(byte b:payload) bcc^=b; if(bcc!=0) reject();
            if(Arrays.equals(payload,new byte[]{8,0,0,0,0,0x10,0,0x18}) || Arrays.equals(payload,new byte[]{8,15,0,0,0,0,0,7})) return;
            int address=((payload[3]&255)<<8)|(payload[4]&255), length=payload[5]&255;
            if(payload[1]==1 && payload[2]==0 && length>=1 && length<=16 && address+length<=65536) return;
            reject();
        }
        requireProfile(profile);
        if(payload==null || payload.length==0) reject();
        if(history(service,characteristic)) {
            int op=payload[0]&255;
            if(op!=1 && op!=4) reject(); // Report/count only. Never delete or acknowledge records.
            if(payload.length==2 && payload[1]==1) return;
            if(payload.length==5 && payload[1]==3 && payload[2]==1) return;
            reject();
        }
        if(UDS.equals(service) && UCP.equals(characteristic)) {
            if(payload.length!=4 || (payload[0]!=2 && payload[0]!=0x40)) reject();
            int user=payload[1]&255, code=(payload[2]&255)|((payload[3]&255)<<8);
            if(user<1 || user>2 || code>9999) reject();
            return;
        }
        if(CTS.equals(service) && CURRENT_TIME.equals(characteristic)) {
            if(payload.length!=10) reject();
            LocalDateTime time=Commands.readDateTime(payload);
            if(time==null || (payload[7]&255)!=time.getDayOfWeek().getValue() || payload[8]!=0 || payload[9]!=1) reject();
            return;
        }
        if(COMMAND.equals(service) && DATE_TIME.equals(characteristic)) {
            if(payload.length!=7 || Commands.readDateTime(payload)==null) reject();
            return;
        }
        if(profile==ProtocolProfile.STP && vendor(service) && HANDSHAKE.equals(characteristic)) {
            if(isSecureHandshake(payload)) return;
        }
        // Includes every WLP data bank, raw memory access, firmware commands, and unknown target.
        reject();
    }
    public static void validateDescriptor(ProtocolProfile profile,UUID service,UUID characteristic,UUID descriptor,byte[] payload) {
        if(profile!=null && profile.isX7()) {
            if(ALIAS.equals(service) && (WLP_RX.equals(characteristic) || HANDSHAKE.equals(characteristic)) && CCCD.equals(descriptor) && Arrays.equals(payload,new byte[]{1,0})) return;
            reject();
        }
        requireProfile(profile);
        if(!CCCD.equals(descriptor) || payload==null || payload.length!=2 || payload[1]!=0 || (payload[0]!=1 && payload[0]!=2)) reject();
        if(BP.equals(service) && MEASUREMENT.equals(characteristic) || history(service,characteristic) || UDS.equals(service) && UCP.equals(characteristic)) return;
        if(profile==ProtocolProfile.STP && vendor(service) && HANDSHAKE.equals(characteristic)) return;
        reject();
    }
    private static boolean isSecureHandshake(byte[] payload) {
        if(payload.length<2 || payload[0]!=0x70) return false;
        int opcode=payload[1]&255;
        return opcode==1 && payload.length==89 || (opcode==5 || opcode==6) && payload.length==46;
    }
    /**
     * Static gate for a memory-write aspect on an exact verified model identity. It never
     * authorizes bytes: every frame must still pass the model-bound validators above.
     * UNTESTED aspects pass only with this request's explicit override; BLOCKED never.
     */
    public static void requireWriteAspect(String modelIdentity,WriteAspectIndex.Aspect aspect,boolean override) {
        if(aspect==null || !WriteAspectIndex.permits(WriteAspectIndex.lookup(modelIdentity,aspect).status,override))
            throw new IllegalArgumentException("Blocked device write: aspect not permitted for this model");
    }
    private static void reject() { throw new IllegalArgumentException("Blocked device write: target or payload is outside the BP control allowlist"); }
}
