package org.openpressure.protocol.fixture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openpressure.protocol.BloodPressure;
import org.openpressure.protocol.DeviceWritePolicy;
import org.openpressure.protocol.LegacyUnlock;
import org.openpressure.protocol.VendorMemoryIndex;
import org.openpressure.protocol.VendorMemoryModel;
import org.openpressure.protocol.VendorWlpSession;

/**
 * Replays a fixture against OpenPressure's Android-independent unlock and vendor read sessions, passing every
 * write through the same {@link DeviceWritePolicy} checks the app applies. The Current Time write belongs to the
 * app service, so steps on the {@code clock} alias are outside this replay and covered by the app-level replay.
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
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL,Set.of("clock"));
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

    private static List<Fixture.Reading> decode(VendorMemoryModel model,List<VendorMemoryModel.Range> ranges,byte[] data) {
        List<Fixture.Reading> readings=new ArrayList<>();
        int offset=0;
        for(VendorMemoryModel.Range range:ranges) {
            // The settings/index image (user 0) holds no records.
            if(range.user!=0) for(int i=0;i<range.length;i+=model.recordBytes) {
                BloodPressure bp=model.parse(java.util.Arrays.copyOfRange(data,offset+i,offset+i+model.recordBytes),range.user);
                if(bp!=null) readings.add(new Fixture.Reading(range.user,(int)bp.systolic,(int)bp.diastolic,bp.pulse.intValue(),bp.time));
            }
            offset+=range.length;
        }
        return readings;
    }
}
