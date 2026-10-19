package org.openpressure.protocol.fixture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openpressure.protocol.BloodPressure;
import org.openpressure.protocol.DeviceWritePolicy;
import org.openpressure.protocol.LegacyUnlock;
import org.openpressure.protocol.VendorClockWrite;
import org.openpressure.protocol.VendorMemoryIndex;
import org.openpressure.protocol.VendorMemoryModel;
import org.openpressure.protocol.VendorWlpSession;
import org.openpressure.protocol.WlpConnection;
import org.openpressure.protocol.WlpReadSession;
import org.openpressure.protocol.WriteAspectIndex;
import org.openpressure.protocol.ProtocolProfile;
import org.openpressure.protocol.X7MemoryIndex;
import org.openpressure.protocol.X7Model;
import org.openpressure.protocol.X7Record;
import org.openpressure.protocol.X7Registration;
import org.openpressure.protocol.X7Security;
import org.openpressure.protocol.X7Transaction;

/**
 * Replays a fixture against OpenPressure's Android-independent unlock, vendor read sessions and clock-block write
 * plan, or the plain single-channel X7 transaction, read session and profile registration, passing every write
 * through the same {@link DeviceWritePolicy} checks the app applies.
 */
public final class ProtocolReplay {
    private ProtocolReplay() {}

    /** Prints each fixture's protocol-level outcome and its annotated differences (open findings). */
    public static void main(String[] args) {
        for(String name:args.length==0?Fixture.all():List.of(args)) {
            Fixture f=Fixture.load(name);
            System.out.println("ours (protocol): "+run(f).report().replace("\n","\n  "));
            for(Fixture.Annotation annotation:f.annotated()) System.out.println("open finding (line "+annotation.line+", "+annotation.only+"-only): "+annotation.reason);
        }
    }

    public static Outcome run(Fixture f) {
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL);
        X7Model x7=X7Model.identify(f.dis.get("model"));
        if(x7!=null) return runX7(f,x7,cursor);
        VendorMemoryModel model=VendorMemoryModel.identify(f.dis.get("model"));
        if(model==null) return Outcome.rejected(0,"model "+f.dis.get("model")+" not accepted");
        if(f.key==null) return Outcome.rejected(0,"fixture has no key");
        UUID service=VendorWlpSession.service(model),handshake=VendorWlpSession.unlockCharacteristic();
        List<UUID> tx=VendorWlpSession.txChannels(model),rx=VendorWlpSession.rxChannels(model);
        try {
            LegacyUnlock unlock=f.flow.equals("pair")?LegacyUnlock.pair(f.key):LegacyUnlock.connect(f.key);
            while(!unlock.complete()) {
                byte[] command=unlock.command();
                DeviceWritePolicy.validateVendorUnlock(model,service,handshake,command,unlock);
                Outcome diverged=cursor.onWrite(handshake,command);
                if(diverged!=null) return diverged;
                List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
                if(replies.isEmpty()) return cursor.stalled();
                for(FixtureCursor.Notification reply:replies) {
                    if(!reply.characteristic.equals(handshake)) return Outcome.rejected(cursor.position(),"unlock reply on unexpected characteristic "+reply.characteristic);
                    unlock.accept(reply.value);
                }
            }
            if(f.flow.equals("sync-clock")) return syncClock(f,model,service,tx,rx,cursor);
            // Like MonitorService: an index-supported model reads its settings/index image, then only the populated
            // slots of the selected users (every slot of their banks when the index is not trusted). A read-all flow,
            // or a model without index support, sweeps the selected banks; read-all adds unconfirmed banks last.
            boolean sweep=f.flow.equals("read-all"),indexed=!sweep && model.indexSupported();
            VendorWlpSession session;
            if(indexed) session=new VendorWlpSession(model,List.of(model.settingsRange()),Math.min(56,VendorWlpSession.maxBlock(model)));
            else {
                List<VendorMemoryModel.Range> ranges=new ArrayList<>(model.historyRanges(f.importUsers));
                if(sweep) for(VendorMemoryModel.Range bank:model.historyRanges()) if(model.unconfirmedBank(bank.user) && !ranges.contains(bank)) ranges.add(bank);
                session=new VendorWlpSession(model,ranges,VendorWlpSession.recordBlock(model));
            }
            ArrayDeque<VendorMemoryModel.Range> pending=new ArrayDeque<>();
            boolean indexRead=false;
            while(!session.complete()) {
                // Decide and append before requesting the next command, which would otherwise commit END.
                if(indexed && !indexRead && session.rangeComplete()) {
                    indexRead=true;
                    try { pending.addAll(VendorMemoryIndex.parse(model,session.data()).populatedRanges(f.importUsers)); }
                    catch(IllegalArgumentException untrusted) { pending.addAll(model.historyRanges(f.importUsers)); }
                }
                if(session.rangeComplete() && !pending.isEmpty()) session.appendRange(pending.removeFirst());
                List<byte[]> parts=VendorWlpSession.chunks(session.command(),tx.size());
                for(int i=0;i<parts.size();i++) {
                    DeviceWritePolicy.validateVendorCharacteristic(model,service,tx.get(i),parts.get(i));
                    Outcome diverged=cursor.onWrite(tx.get(i),parts.get(i));
                    if(diverged!=null) return diverged;
                }
                List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
                if(replies.isEmpty()) return cursor.stalled();
                for(FixtureCursor.Notification reply:replies) {
                    int channel=rx.indexOf(reply.characteristic);
                    if(channel<0) return Outcome.rejected(cursor.position(),"reply on unexpected characteristic "+reply.characteristic);
                    try { session.accept(channel,reply.value); }
                    catch(IllegalArgumentException rejected) {
                        // Read all ends early when a bank Omron's configuration lacks answers with a device status.
                        VendorMemoryModel.Range current=session.currentRange();
                        if(!sweep || !session.readStatusError() || current==null || !model.unconfirmedBank(current.user)) throw rejected;
                        session.abandonRemainingRanges();
                    }
                }
            }
            return cursor.finish(decode(model,session.ranges(),session.data()));
        } catch(IllegalArgumentException refused) {
            return Outcome.rejected(cursor.position(),"OpenPressure refused: "+refused.getMessage());
        }
    }

    /**
     * Like MonitorService's explicit clock sync: START and the settings image, a plan targeting the fixture's
     * {@code time} (the developer override stands in for the untested write aspect, as the app replay enables it),
     * the chunks on their own TX channels through the GATT-boundary validator, the acknowledgment on RX, then END.
     * A within-threshold clock writes nothing; a rejected acknowledgment still ends the session.
     */
    private static Outcome syncClock(Fixture f,VendorMemoryModel model,UUID service,List<UUID> tx,List<UUID> rx,FixtureCursor cursor) {
        VendorWlpSession session=new VendorWlpSession(model,List.of(model.settingsRange()),Math.min(56,VendorWlpSession.maxBlock(model)));
        String clock=null;
        while(!session.complete()) {
            if(clock==null && session.rangeComplete()) {
                VendorClockWrite plan;
                try { plan=new VendorClockWrite(WriteAspectIndex.resolve(f.dis.get("model")),session.data(),f.time,true,tx.size()); }
                catch(IllegalArgumentException unreadable) { plan=null; clock="not_attempted"; }
                if(plan!=null && plan.writesNothing()) clock="skipped";
                else if(plan!=null) {
                    plan.authorize();
                    List<byte[]> chunks=plan.chunks();
                    for(int i=0;i<chunks.size();i++) {
                        DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(i),chunks.get(i),plan);
                        Outcome diverged=cursor.onWrite(tx.get(i),chunks.get(i));
                        if(diverged!=null) return diverged;
                    }
                    List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
                    if(replies.isEmpty()) return cursor.stalled();
                    for(FixtureCursor.Notification reply:replies) {
                        int channel=rx.indexOf(reply.characteristic);
                        if(channel<0) return Outcome.rejected(cursor.position(),"reply on unexpected characteristic "+reply.characteristic);
                        try { if(plan.accept(channel,reply.value)) clock="acknowledged"; }
                        catch(IllegalArgumentException rejected) { clock="rejected"; break; }
                    }
                    if(clock==null) return Outcome.rejected(cursor.position(),"clock write acknowledgment incomplete");
                }
            }
            List<byte[]> parts=VendorWlpSession.chunks(session.command(),tx.size());
            for(int i=0;i<parts.size();i++) {
                DeviceWritePolicy.validateVendorCharacteristic(model,service,tx.get(i),parts.get(i));
                Outcome diverged=cursor.onWrite(tx.get(i),parts.get(i));
                if(diverged!=null) return diverged;
            }
            List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
            if(replies.isEmpty()) return cursor.stalled();
            for(FixtureCursor.Notification reply:replies) {
                int channel=rx.indexOf(reply.characteristic);
                if(channel<0) return Outcome.rejected(cursor.position(),"reply on unexpected characteristic "+reply.characteristic);
                session.accept(channel,reply.value);
            }
        }
        return cursor.finish(List.of(),clock);
    }

    /** FE4A, the single-channel X7 WLP service. */
    private static final UUID X7_SERVICE=UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb");
    /** MonitorService's MTU request; the negotiated MTU is the smaller of it and the fixture's {@code mtu}. */
    private static final int REQUESTED_MTU=243;

    /**
     * Like MonitorService on a plain-advertising X7 without a saved key: the SYS transaction with a fresh random
     * nonce, then one WLP session. Connect reads the 68-byte settings/index image and the populated slots of the
     * selected users; Pair reads the image and, when the write-aspect index permits registration, runs the fixture
     * user's profile registration (two staged writes) before END. The encrypted route is not replayed here.
     */
    private static Outcome runX7(Fixture f,X7Model model,FixtureCursor cursor) {
        for(String alias:new String[]{"tx","rx","sys"})
            if(!f.aliases.containsKey(alias) || f.uuids(alias).size()!=1) return Outcome.rejected(0,"an X7 fixture needs single-characteristic tx, rx and sys aliases");
        UUID tx=f.uuids("tx").get(0),rx=f.uuids("rx").get(0),sys=f.uuids("sys").get(0);
        X7Security.Mode advertised=X7Security.fromManufacturerPayload(f.omronManufacturerPayload());
        if(advertised!=X7Security.Mode.PLAIN) return Outcome.rejected(0,"X7 advertisement "+advertised+": only the plain route is replayed at protocol level");
        if(!f.flow.equals("connect") && !f.flow.equals("pair")) return Outcome.rejected(0,"X7 flow "+f.flow+" is not replayed at protocol level");
        int mtu=Math.min(REQUESTED_MTU,f.mtu);
        boolean pair=f.flow.equals("pair");
        try {
            byte[] nonce=new byte[4];
            new java.security.SecureRandom().nextBytes(nonce);
            X7Transaction transaction=new X7Transaction(nonce);
            byte[] request=transaction.request();
            DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,X7_SERVICE,sys,request);
            Outcome diverged=cursor.onWrite(sys,request);
            if(diverged!=null) return diverged;
            List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
            if(replies.isEmpty()) return cursor.stalled();
            for(FixtureCursor.Notification reply:replies) {
                if(!reply.characteristic.equals(sys)) return Outcome.rejected(cursor.position(),"transaction reply on unexpected characteristic "+reply.characteristic);
                transaction.accept(reply.value);
            }
            if(!transaction.ready()) return Outcome.rejected(cursor.position(),"X7 transaction not confirmed");
            String identity=WriteAspectIndex.resolve(f.dis.get("model"));
            if(pair && !WriteAspectIndex.permits(WriteAspectIndex.lookup(identity,WriteAspectIndex.Aspect.REGISTRATION).status,false))
                // MonitorService ends Pair in the read-only ready state without a read.
                return cursor.finish(List.of());
            if(pair && mtu<35) return Outcome.rejected(cursor.position(),"OpenPressure refused: X7 registration requires an ATT MTU of at least 35");
            WlpReadSession session=new WlpConnection().begin(X7MemoryIndex.SETTINGS_START,X7MemoryIndex.SETTINGS_BYTES,mtu>=27?16:8);
            List<X7MemoryIndex.Range> ranges=new ArrayList<>();
            ArrayDeque<X7MemoryIndex.Range> pending=new ArrayDeque<>();
            boolean indexRead=false;
            while(!session.complete()) {
                if(!indexRead && session.rangeComplete()) {
                    indexRead=true;
                    if(pair) {
                        X7Registration registration=new X7Registration(f.dis.get("model"),session.data(),f.registerUser,f.time,false);
                        registration.authorize();
                        while(!registration.complete()) {
                            byte[] command=registration.command();
                            DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,X7_SERVICE,tx,command,registration);
                            if((diverged=cursor.onWrite(tx,command))!=null) return diverged;
                            if((diverged=deliver(cursor,rx,registration::accept))!=null) return diverged;
                        }
                    } else {
                        // Like MonitorService, an untrusted X7 index fails the import instead of sweeping.
                        ranges.addAll(X7MemoryIndex.parse(model,session.data()).historyRanges(f.importUsers));
                        pending.addAll(ranges);
                    }
                }
                if(session.rangeComplete() && !pending.isEmpty()) { X7MemoryIndex.Range next=pending.removeFirst(); session.appendRange(next.start,next.length); }
                byte[] command=session.command();
                DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,X7_SERVICE,tx,command);
                if((diverged=cursor.onWrite(tx,command))!=null) return diverged;
                if((diverged=deliver(cursor,rx,session::accept))!=null) return diverged;
            }
            return cursor.finish(decodeX7(model,ranges,session.data()));
        } catch(IllegalArgumentException refused) {
            return Outcome.rejected(cursor.position(),"OpenPressure refused: "+refused.getMessage());
        }
    }
    /** Feeds the scripted notifications to a single-channel parser; null when every one arrived on {@code rx}. */
    private static Outcome deliver(FixtureCursor cursor,UUID rx,java.util.function.Predicate<byte[]> parser) {
        List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
        if(replies.isEmpty()) return cursor.stalled();
        for(FixtureCursor.Notification reply:replies) {
            if(!reply.characteristic.equals(rx)) return Outcome.rejected(cursor.position(),"reply on unexpected characteristic "+reply.characteristic);
            parser.test(reply.value);
        }
        return null;
    }
    /** X7 records after the 68-byte image, in range order; malformed slots are rejected slots, as in MonitorService. */
    private static List<Fixture.Reading> decodeX7(X7Model model,List<X7MemoryIndex.Range> ranges,byte[] data) {
        List<Fixture.Reading> readings=new ArrayList<>();
        int offset=X7MemoryIndex.SETTINGS_BYTES;
        for(X7MemoryIndex.Range range:ranges) {
            for(int i=0;i+X7Record.RECORD_BYTES<=range.length;i+=X7Record.RECORD_BYTES) {
                X7Record record;
                try { record=X7Record.parse(java.util.Arrays.copyOfRange(data,offset+i,offset+i+X7Record.RECORD_BYTES),range.user,model); }
                catch(IllegalArgumentException rejected) { continue; }
                if(record!=null) readings.add(new Fixture.Reading(range.user,(int)record.measurement.systolic,(int)record.measurement.diastolic,record.measurement.pulse.intValue(),record.measurement.time)
                    .withFlags(flag(record.measurement.status,4),flag(record.measurement.status,1),record.measurement.atrialFibrillation==null?null:record.measurement.atrialFibrillation?1:0));
            }
            offset+=range.length;
        }
        return readings;
    }

    /** One status bit (4 irregular pulse, 1 body movement) as 0/1, or null without a status. */
    static Integer flag(Integer status,int bit) { return status==null?null:(status&bit)!=0?1:0; }

    private static List<Fixture.Reading> decode(VendorMemoryModel model,List<VendorMemoryModel.Range> ranges,byte[] data) {
        List<Fixture.Reading> readings=new ArrayList<>();
        int offset=0;
        for(VendorMemoryModel.Range range:ranges) {
            // The settings/index image (user 0) holds no records.
            if(range.user!=0) for(int i=0;i<range.length;i+=model.recordBytes) {
                // Like MonitorService, a malformed record (e.g. a wrong record checksum) is a rejected slot, not a failed session.
                BloodPressure bp;
                try { bp=model.parse(java.util.Arrays.copyOfRange(data,offset+i,offset+i+model.recordBytes),range.user); }
                catch(IllegalArgumentException rejected) { continue; }
                if(bp!=null) readings.add(new Fixture.Reading(range.user,(int)bp.systolic,(int)bp.diastolic,bp.pulse.intValue(),bp.time));
            }
            offset+=range.length;
        }
        return readings;
    }
}
