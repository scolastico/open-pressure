package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Populated BP slots from a fresh HEM-7380T1-EOSL settings/index read. */
public final class X7MemoryIndex {
    public static final int SETTINGS_START=0x10, SETTINGS_BYTES=68;
    private final int user1,user2;

    private X7MemoryIndex(int user1,int user2) { this.user1=user1; this.user2=user2; }

    public static X7MemoryIndex parse(byte[] settings) {
        if(settings==null || settings.length!=SETTINGS_BYTES)
            throw new IllegalArgumentException("X7 index requires exactly 68 fresh settings bytes");
        // The model skips checksum blocks 1 and 2. The original read path checks
        // block 3 with an additive byte checksum, ignoring its final reserved byte.
        int checksum=0;
        for(int i=44;i<58;i++) checksum=(checksum+(settings[i]&255))&255;
        if(checksum!=(settings[58]&255)) throw new IllegalArgumentException("X7 settings checksum does not match");
        return new X7MemoryIndex(populated(settings,0),populated(settings,2));
    }

    private static int populated(byte[] settings,int offset) {
        int pointer=settings[offset]&255;
        if(pointer>X7Record.RECORDS_PER_USER) throw new IllegalArgumentException("Invalid X7 history pointer");
        // Layout OF00000011111111: F is bit 14, pointer is the low byte.
        // With latest-position correction -1, a nonfull pointer is its record count.
        return (settings[offset+1]&0x40)!=0?X7Record.RECORDS_PER_USER:pointer;
    }

    public int count(int user) {
        if(user<1 || user>2) throw new IllegalArgumentException("X7 user must be 1 or 2");
        return user==1?user1:user2;
    }

    public List<Range> historyRanges() {
        List<Range> ranges=new ArrayList<>();
        for(int user=1;user<=2;user++) {
            int count=count(user);
            if(count>0) ranges.add(new Range(X7Record.START+(user-1)*X7Record.RECORDS_PER_USER*X7Record.RECORD_BYTES,count*X7Record.RECORD_BYTES,user));
        }
        return Collections.unmodifiableList(ranges);
    }

    public static final class Range {
        public final int start,length,user;
        private Range(int start,int length,int user) { this.start=start; this.length=length; this.user=user; }
    }
}
