package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Populated slots from a fresh legacy settings/index image. Logical prefix, little-endian:
 * position u16 at 2*(u-1), unsent u16 at 4+2*(u-1) (bit 15 is parity), sequence u32 at 8+4*(u-1).
 */
public final class VendorMemoryIndex {
    private final VendorMemoryModel model;
    private final byte[] logical;

    private VendorMemoryIndex(VendorMemoryModel model,byte[] logical) { this.model=model; this.logical=logical; }

    public static VendorMemoryIndex parse(VendorMemoryModel model,byte[] image) {
        if(model==null || !model.indexSupported() || image==null || image.length!=model.settingsLength)
            throw new IllegalArgumentException("Vendor index requires a supported model and its exact settings image");
        byte[] logical=model.logical(image);
        // Only the HEM-7530T read path is evidenced to check block 3 (bytes 44..57 sum into byte 58).
        if(model.id.equals("HEM-7530T") && VendorMemoryModel.checksum(Arrays.copyOfRange(logical,44,58),14)!=(logical[58]&255))
            throw new IllegalArgumentException("Vendor index block checksum does not match");
        return new VendorMemoryIndex(model,logical);
    }

    public int position(int user) { return u16(2*(index(user)-1)); }
    /** Informational only: the unsent count, without its parity bit. */
    public int unsent(int user) { return u16(4+2*(index(user)-1))&0x7fff; }
    public long sequence(int user) {
        int o=8+4*(index(user)-1);
        return u16(o)|((long)u16(o+2)<<16);
    }

    /** Below capacity the position must equal the sequence; a full bank needs only an in-range position. */
    public boolean trusted(int user) {
        if(user<1 || user>model.userCount) return false;
        int position=position(user); long sequence=sequence(user);
        if(position>model.capacity()) return false;
        return sequence>=model.capacity() || position==sequence;
    }

    public int populated(int user) {
        if(!trusted(user)) throw new IllegalArgumentException("Vendor index for user "+user+" is not trusted");
        return (int)Math.min(sequence(user),model.capacity());
    }

    /** Slots 0..populated-1 of each selected user; throws if a selected user's index is untrusted. */
    public List<VendorMemoryModel.Range> populatedRanges(int users) {
        List<VendorMemoryModel.Range> ranges=new ArrayList<>();
        for(VendorMemoryModel.Range bank:model.historyRanges()) {
            if(!model.selectable(bank.user,users)) continue;
            int count=populated(bank.user);
            if(count>0) ranges.add(VendorMemoryModel.range(bank.start,count*model.recordBytes,bank.user));
        }
        return Collections.unmodifiableList(ranges);
    }

    private int index(int user) {
        if(user<1 || user>model.userCount) throw new IllegalArgumentException("Vendor index has no user "+user);
        return user;
    }
    private int u16(int offset) { return (logical[offset]&255)|((logical[offset+1]&255)<<8); }
}
