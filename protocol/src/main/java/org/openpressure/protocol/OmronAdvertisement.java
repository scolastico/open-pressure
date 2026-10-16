package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android getManufacturerSpecificData(0x020e) already removes the company prefix. */
public final class OmronAdvertisement {
    public final boolean clockUnset, pairingMode, standardMode, streamingMode, forcedTransfer;
    public final List<User> users;
    private OmronAdvertisement(int flags,List<User> users) {
        clockUnset=(flags & 4)!=0; pairingMode=(flags & 8)!=0; standardMode=(flags & 32)!=0;
        streamingMode=(flags & 16)!=0; forcedTransfer=(flags & 64)!=0;
        this.users=Collections.unmodifiableList(users);
    }
    public static OmronAdvertisement parseManufacturerPayload(byte[] bytes) {
        Packet p=new Packet(bytes);
        int type=p.u8();
        if (type!=1 && type!=2 && type!=6) throw new IllegalArgumentException("Unknown Omron advertisement type");
        int flags=p.u8(), count=(flags & 3)+1;
        List<User> users=new ArrayList<>();
        for(int i=0;i<count;i++) users.add(new User(p.u16(),p.u8()));
        return new OmronAdvertisement(flags,users);
    }
    /** Bank metadata only; mode flags and the optional error trailer are transient. */
    public String revision() {
        StringBuilder value=new StringBuilder("v1");
        for(User user:users) value.append(':').append(user.lastSequence).append('/').append(user.recordCount);
        return value.toString();
    }
    public static final class User {
        public final int lastSequence, recordCount;
        User(int sequence,int count) { lastSequence=sequence; recordCount=count; }
    }
}
