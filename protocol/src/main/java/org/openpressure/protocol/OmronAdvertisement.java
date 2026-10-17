package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Omron manufacturer data. Android getManufacturerSpecificData(0x020e) already removes the company prefix,
 * so every offset here is one byte type, then one flags byte. Layouts follow the original scan filter.
 */
public final class OmronAdvertisement {
    public final int type;
    public final boolean clockUnset, pairingMode, standardMode, streamingMode, forcedTransfer;
    /** Encrypted types 8/9 name their category and model; other types report -1. */
    public final int category, modelId;
    public final List<User> users;
    /** Discovery hint only; the Device Information identity still selects the protocol/map. */
    public static boolean hasKnownName(String name) {
        return name!=null && name.length()>9 && name.regionMatches(true,0,"BLEsmart_",0,9);
    }
    private OmronAdvertisement(int type,int flags,int category,int modelId,List<User> users) {
        this.type=type; this.category=category; this.modelId=modelId;
        clockUnset=(flags & 4)!=0; pairingMode=(flags & 8)!=0;
        // Type 3 uses bits 4-5 for guidance mode instead of streaming/standard mode.
        standardMode=type!=3 && (flags & 32)!=0; streamingMode=type!=3 && (flags & 16)!=0;
        forcedTransfer=type!=3 && (flags & 64)!=0;
        this.users=Collections.unmodifiableList(users);
    }
    public static OmronAdvertisement parseManufacturerPayload(byte[] bytes) {
        Packet p=new Packet(bytes);
        int type=p.u8(), flags=p.u8();
        List<User> users=new ArrayList<>();
        switch(type) {
            case 1: case 2: case 6: {
                // Per bank: uint16 LE last sequence and stored-record count. A type-6 error trailer may follow.
                for(int i=0;i<(flags & 3)+1;i++) users.add(new User(p.u16(),p.u8()));
                return new OmronAdvertisement(type,flags,-1,-1,users);
            }
            case 3:
                // Result identifier only. The original filter cannot compare sequences for this type.
                p.u8();
                return new OmronAdvertisement(type,flags,-1,-1,users);
            case 8: case 9: {
                int count=(flags & 3)+1;
                // The original filter accepts only these exact lengths and reports no record counts.
                if(type==8 && !(count==1 && bytes.length==10 || count==2 && bytes.length==13)
                        || type==9 && bytes.length!=7+2*count)
                    throw new IllegalArgumentException("Unexpected encrypted Omron advertisement length");
                p.u8(); if(type==9) p.u8();
                int category=p.u8(), model=p.u16();
                for(int i=0;i<count;i++) users.add(new User(p.u16(),-1));
                return new OmronAdvertisement(type,flags,category,model,users);
            }
            default: throw new IllegalArgumentException("Unknown Omron advertisement type");
        }
    }
    /** Only types 1, 2 and 6 carry the stored-record counts used for import checkpoints. */
    public boolean hasRecordCounts() { return type==1 || type==2 || type==6; }
    /** Bank metadata only; mode flags and the optional error trailer are transient. Null without record counts. */
    public String revision() {
        if(!hasRecordCounts()) return null;
        StringBuilder value=new StringBuilder("v1");
        for(User user:users) value.append(':').append(user.lastSequence).append('/').append(user.recordCount);
        return value.toString();
    }
    public static final class User {
        /** Record count is -1 when the advertisement type does not report it. */
        public final int lastSequence, recordCount;
        User(int sequence,int count) { lastSequence=sequence; recordCount=count; }
    }
}
