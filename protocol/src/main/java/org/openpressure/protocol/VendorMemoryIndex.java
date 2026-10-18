package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Populated slots from a fresh legacy settings/index image. Logical prefix, little-endian:
 * position word u16 at 2*(u-1) (next-write position in the low byte, bit 14 = bank full, as on the X7),
 * unsent u16 at 4+2*(u-1) (count in the low 15 bits), sequence u32 at 8+4*(u-1).
 * Field getters and {@link #trusted} accept every bank the model has, while
 * {@link #populatedRanges} follows the selectable users exactly like {@link VendorMemoryModel#historyRanges(int)}.
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

    /** Next-write position: the low byte of the position word. */
    public int position(int user) { return u16(2*(index(user)-1))&255; }
    /** Bit 14 of the position word: every slot of the bank is populated. Bit 15 and bits 8-13 are ignored. */
    public boolean full(int user) { return (u16(2*(index(user)-1))&0x4000)!=0; }
    /** Informational only: the low 15 bits of the unsent field. */
    public int unsent(int user) { return u16(4+2*(index(user)-1))&0x7fff; }
    /** Informational only; it does not decide which slots are populated. */
    public long sequence(int user) {
        int o=8+4*(index(user)-1);
        return u16(o)|((long)u16(o+2)<<16);
    }

    /** Omron's rule (and X7MemoryIndex's): the next-write position must lie within the bank. */
    public boolean trusted(int user) {
        if(user<1 || user>model.userCount) return false;
        return position(user)<=model.capacity();
    }

    /** A full bank populates every slot; otherwise slots 0..position-1. */
    public int populated(int user) {
        if(!trusted(user)) throw new IllegalArgumentException("Vendor index for user "+user+" is not trusted");
        return full(user)?model.capacity():position(user);
    }

    /**
     * Slots 0..populated-1 of each selected user, selected exactly as {@link VendorMemoryModel#historyRanges(int)}
     * (an empty or unsupported selection means user 1); throws if a selected user's index is untrusted.
     */
    public List<VendorMemoryModel.Range> populatedRanges(int users) {
        List<VendorMemoryModel.Range> ranges=new ArrayList<>();
        // Index-supported models never use a shared bank, so each bank here is one selected user.
        for(VendorMemoryModel.Range bank:model.historyRanges(users)) {
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
