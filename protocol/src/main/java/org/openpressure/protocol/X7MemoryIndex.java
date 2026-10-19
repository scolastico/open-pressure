package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Populated BP slots from a fresh X7-family settings/index read, bound to the verified model's banks. */
public final class X7MemoryIndex {
    public static final int SETTINGS_START=X7Model.SETTINGS_START, SETTINGS_BYTES=X7Model.SETTINGS_BYTES;
    private final X7Model model;
    private final int user1,user2;

    private X7MemoryIndex(X7Model model,int user1,int user2) { this.model=model; this.user1=user1; this.user2=user2; }

    /** The model must be the one resolved from the verified Device Information identity. */
    public static X7MemoryIndex parse(X7Model model,byte[] settings) {
        if(model==null) throw new IllegalArgumentException("X7 index requires its verified model");
        if(settings==null || settings.length!=SETTINGS_BYTES)
            throw new IllegalArgumentException("X7 index requires exactly 68 fresh settings bytes");
        // The model skips checksum blocks 1 and 2. The original read path checks
        // block 3 with an additive byte checksum, ignoring its final reserved byte.
        int checksum=0;
        for(int i=44;i<58;i++) checksum=(checksum+(settings[i]&255))&255;
        if(checksum!=(settings[58]&255)) throw new IllegalArgumentException("X7 settings checksum does not match");
        return new X7MemoryIndex(model,populated(model,settings,0),model.userCount==2?populated(model,settings,2):0);
    }

    private static int populated(X7Model model,byte[] settings,int offset) {
        int pointer=settings[offset]&255;
        if(pointer>model.recordsPerUser) throw new IllegalArgumentException("Invalid X7 history pointer");
        // Layout OF00000011111111: F is bit 14, pointer is the low byte.
        // With latest-position correction -1, a nonfull pointer is its record count.
        return (settings[offset+1]&0x40)!=0?model.recordsPerUser:pointer;
    }

    /** The model whose banks this index addresses. */
    public X7Model model() { return model; }

    public int count(int user) {
        model.requireUser(user);
        return user==1?user1:user2;
    }

    public List<Range> historyRanges() {
        List<Range> ranges=new ArrayList<>();
        for(int user=1;user<=model.userCount;user++) {
            int count=count(user);
            if(count>0) ranges.add(new Range(model.bankStart(user),count*model.recordBytes,user));
        }
        return Collections.unmodifiableList(ranges);
    }

    /**
     * Populated ranges of the selected users only (bitmask: 1 = user 1, 2 = user 2); a selection without
     * either user falls back to user 1, as for the vendor-memory models.
     */
    public List<Range> historyRanges(int users) {
        users&=(1<<model.userCount)-1;
        if(users==0) users=1;
        List<Range> selected=new ArrayList<>();
        for(Range range:historyRanges()) if((users&(1<<(range.user-1)))!=0) selected.add(range);
        return Collections.unmodifiableList(selected);
    }

    /** Populated slots of the selected users, with the same fallback as {@link #historyRanges(int)}. */
    public int selectedCount(int users) {
        int total=0;
        for(Range range:historyRanges(users)) total+=range.length/model.recordBytes;
        return total;
    }

    public static final class Range {
        public final int start,length,user;
        private Range(int start,int length,int user) { this.start=start; this.length=length; this.user=user; }
    }
}
