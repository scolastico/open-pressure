package org.openpressure.protocol.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openpressure.protocol.BloodPressure;
import org.openpressure.protocol.DeviceWritePolicy;
import org.openpressure.protocol.LegacyUnlock;
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
            for(Fixture.Step step:f.annotated()) System.out.println("open finding (line "+step.line+", "+step.only+"-only): "+step.reason);
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
            VendorWlpSession session=new VendorWlpSession(model);
            while(!session.complete()) {
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
            return cursor.finish(decode(model,session.data()));
        } catch(IllegalArgumentException refused) {
            return Outcome.rejected(cursor.position(),"OpenPressure refused: "+refused.getMessage());
        }
    }

    private static List<Fixture.Reading> decode(VendorMemoryModel model,byte[] data) {
        List<Fixture.Reading> readings=new ArrayList<>();
        int offset=0;
        for(VendorMemoryModel.Range range:model.historyRanges()) {
            for(int i=0;i<range.length;i+=model.recordBytes) {
                BloodPressure bp=model.parse(java.util.Arrays.copyOfRange(data,offset+i,offset+i+model.recordBytes),range.user);
                if(bp!=null) readings.add(new Fixture.Reading(range.user,(int)bp.systolic,(int)bp.diastolic,bp.pulse.intValue(),bp.time));
            }
            offset+=range.length;
        }
        return readings;
    }
}
