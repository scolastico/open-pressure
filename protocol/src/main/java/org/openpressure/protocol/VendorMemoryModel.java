package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only memory facts for the non-X7 models compared in docs/omblepy-comparison.md.
 * Implemented independently from the documented addresses and field descriptions;
 * these source-based maps do not establish hardware validation in OpenPressure.
 */
public final class VendorMemoryModel {
    private static final VendorMemoryModel[] MODELS={
        new VendorMemoryModel("HEM-7150T",16,0x10,68,true,60,0x98),
        new VendorMemoryModel("HEM-7155T",16,0x10,68,true,60,0x98,0x458),
        new VendorMemoryModel("HEM-7156T",16,0x10,68,true,60,0x98,0x458),
        new VendorMemoryModel("HEM-7322T",14,0x260,38,true,100,0x2ac,0x824),
        new VendorMemoryModel("HEM-7342T",16,0x10,68,true,100,0x98,0x6d8),
        new VendorMemoryModel("HEM-7361T",16,0x10,68,true,100,0x98,0x6d8),
        // Pinned upstream BP5360 variant: the original APK has a different split map.
        // Keep this read map tied to its separately guarded single-channel, bonded transport.
        new VendorMemoryModel("HEM-7377T1",16,0x40,24,false,100,0x1cc,0x80c),
        new VendorMemoryModel("HEM-7530T",14,0x260,68,true,90,0x2e8),
        new VendorMemoryModel("HEM-7600T",14,0x260,38,true,100,0x2ac),
        new VendorMemoryModel("HEM-6232T",14,0x260,68,true,100,0x2e8,0x860)
    };
    public final String id;
    public final boolean legacyUnlock;
    public final int settingsStart,settingsLength,recordBytes,userCount;
    private final List<Range> ranges;

    private VendorMemoryModel(String id,int recordBytes,int settingsStart,int settingsLength,boolean legacyUnlock,int capacity,int... starts) {
        this.id=id; this.recordBytes=recordBytes; this.settingsStart=settingsStart;
        this.settingsLength=settingsLength; this.legacyUnlock=legacyUnlock; this.userCount=starts.length;
        List<Range> ranges=new ArrayList<>();
        for(int i=0;i<starts.length;i++) ranges.add(new Range(starts[i],capacity*recordBytes,i+1));
        this.ranges=Collections.unmodifiableList(ranges);
    }

    /** Exact Device Information identities only; advertisements and marketing hints are insufficient. */
    public static VendorMemoryModel identify(String disModel) {
        if(disModel==null) return null;
        String model=disModel.trim();
        // Exact original-model identities whose addresses, capacities, settings and decoded
        // fields agree with this map. Their configs declare WLS3.0, without per-model
        // channel or unlock overrides. Other variants require separate evidence.
        switch(model) {
            case "HEM-7150T-Z": case "HEM-7150T-CA": model="HEM-7150T"; break;
            case "HEM-7155T-EBK": case "HEM-7155T-D":
            case "HEM-7155T_ESL": case "HEM-7155T-ESL":
            case "HEM-7155T_AP": case "HEM-7155T-AP": model="HEM-7155T"; break;
            case "HEM-7342T-Z": case "HEM-7342T-CA": model="HEM-7342T"; break;
            case "HEM-7361T-EBK": case "HEM-7361T_ESL": case "HEM-7361T-E":
            case "HEM-7361T-D": case "HEM-7361T-AP": model="HEM-7361T"; break;
            // Explicitly reported by the pinned upstream driver, despite the APK map difference.
            case "HEM-7377T1-ZAZ": model="HEM-7377T1"; break;
            case "HEM-7530T-Z": case "HEM-7530T_E3": case "HEM-7530T-E3":
            case "HEM-7530T_J3": case "HEM-7530T-J3":
            case "HEM-7530T_AP3": case "HEM-7530T-AP3":
            case "HEM-7530T_JT3": model="HEM-7530T"; break;
            default: break;
        }
        for(VendorMemoryModel candidate:MODELS) if(candidate.id.equals(model)) return candidate;
        return null;
    }

    /** Full banks, including erased slots. This never relies on, or resets, an unread counter. */
    public List<Range> historyRanges() { return ranges; }

    /** Erased slots return null; malformed records throw; unavailable device timestamps remain null. */
    public BloodPressure parse(byte[] raw,int user) {
        if(raw==null || raw.length!=recordBytes || user<1 || user>userCount)
            throw new IllegalArgumentException("Invalid "+id+" record or bank");
        boolean erased=true;
        for(byte value:raw) erased&=value==(byte)255;
        if(erased) return null;
        // Omron's own code rejects a record whose additive checksum byte is wrong. The upstream
        // BP5360 map has no such evidence (byte 13 is a user marker), so it keeps field checks only.
        if(!id.equals("HEM-7377T1")) {
            byte[] l=logical(raw); int n=recordBytes==16?14:12;
            if(checksum(l,n)!=(l[n]&255)) throw new IllegalArgumentException("Vendor record checksum mismatch");
        }
        boolean little=recordBytes==16;
        int sys=u8(raw,little?0:1)+25, dia=u8(raw,little?1:0), pulse=u8(raw,little?2:3);
        if(dia==0 || sys<=dia || pulse==0)
            throw new IllegalArgumentException("Invalid vendor pressure/pulse fields");
        int year=u8(raw,little?3:2);
        // Big-endian 7322/7600 use a full year byte; the other maps carry six year bits.
        if(little || id.equals("HEM-7530T") || id.equals("HEM-6232T")) year&=63;
        year+=2000;
        int date=word(raw,4,little), clock=word(raw,6,little);
        int month=(date>>>10)&15, day=(date>>>5)&31, hour=date&31;
        int minute=(clock>>>6)&63, second=clock&63;
        if(id.equals("HEM-7377T1")) {
            // The unused first bank may contain summary blocks. These are not BP records.
            if(year<2020 || sys<60 || sys>250 || dia<30 || dia>150 || pulse<30 || pulse>200)
                throw new IllegalArgumentException("BP5360 non-record or out-of-range fields");
            try {
                LocalDate.of(year,month,day);
                if(hour>23 || minute>59) throw new DateTimeException("Invalid BP5360 time");
            } catch(DateTimeException invalid) {
                throw new IllegalArgumentException("BP5360 non-record calendar fields",invalid);
            }
            // Both user populations were observed in bank 2. Bank alone is not an identity.
            int marker=u8(raw,13);
            if(marker==1) user=1;
            else if(marker==255) user=2;
            else throw new IllegalArgumentException("Unknown BP5360 user metadata");
        }
        LocalDateTime time=null;
        try { time=LocalDateTime.of(year,month,day,hour,minute,second); }
        catch(DateTimeException unavailable) { /* Preserve missing or invalid timestamps without clamping. */ }
        boolean wrist=id.equals("HEM-6232T");
        int movementMask=wrist?0x4000:0x8000, irregularMask=wrist?0x8000:0x4000;
        int status=((date&movementMask)!=0?1:0)|((date&irregularMask)!=0?4:0);
        // None of these reviewed maps establishes an AFib field. IHB remains independent.
        return new BloodPressure(sys,dia,Double.NaN,(double)pulse,user,status,time,false);
    }

    /** Logical record order: 14-byte maps arrive with adjacent byte pairs swapped; 16-byte maps are copied unchanged. */
    public byte[] logical(byte[] wire) {
        byte[] l=wire.clone();
        if(recordBytes==14) for(int i=0;i+1<l.length;i+=2) { byte b=l[i]; l[i]=l[i+1]; l[i+1]=b; }
        return l;
    }

    /** 8-bit additive checksum of the first {@code count} logical bytes. */
    public static int checksum(byte[] logical,int count) {
        int sum=0;
        for(int i=0;i<count;i++) sum+=logical[i]&255;
        return sum&255;
    }

    private static int u8(byte[] bytes,int index) { return bytes[index]&255; }
    private static int word(byte[] bytes,int offset,boolean little) {
        return little?u8(bytes,offset)|(u8(bytes,offset+1)<<8):(u8(bytes,offset)<<8)|u8(bytes,offset+1);
    }

    public static final class Range {
        public final int start,length,user;
        private Range(int start,int length,int user) { this.start=start; this.length=length; this.user=user; }
    }
}
