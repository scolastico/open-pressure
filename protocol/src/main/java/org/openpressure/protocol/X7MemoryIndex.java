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

    /**
     * Populated ranges of the selected users only (bitmask: 1 = user 1, 2 = user 2); a selection without
     * either user falls back to user 1, as for the vendor-memory models.
     */
    public List<Range> historyRanges(int users) {
        if((users&3)==0) users=1;
        List<Range> selected=new ArrayList<>();
        for(Range range:historyRanges()) if((users&(1<<(range.user-1)))!=0) selected.add(range);
        return Collections.unmodifiableList(selected);
    }

    /** Populated slots of the selected users, with the same fallback as {@link #historyRanges(int)}. */
    public int selectedCount(int users) {
        int total=0;
        for(Range range:historyRanges(users)) total+=range.length/X7Record.RECORD_BYTES;
        return total;
    }

    public static final class Range {
        public final int start,length,user;
        private Range(int start,int length,int user) { this.start=start; this.length=length; this.user=user; }
    }
}
