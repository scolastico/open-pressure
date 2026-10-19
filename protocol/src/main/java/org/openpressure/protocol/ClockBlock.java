package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;

/**
 * Where a monitor keeps its clock inside the settings image (docs/omron-sandbox.md,
 * "Post-transfer settings writes"). Pure data and byte arithmetic: this sends nothing and
 * authorizes nothing. Images are in logical order; callers un-swap 14-byte maps with
 * {@link VendorMemoryModel#logical(byte[])}. The block's last byte is reserved and kept.
 */
public final class ClockBlock {
    /** Device write address; offsets are relative to the settings image. */
    public final int writeAddress,imageOffset,length,clockOffset,checksumOffset;
    /** Whether the device expects the block with adjacent byte pairs swapped (14-byte maps). */
    public final boolean swapped;
    /**
     * Whether an erased (all-FF) block may be written. Omron's code and OpenPressure's index and
     * registration paths refuse a block 3 with a bad checksum on HEM-7380T1-EOSL/EBK, HEM-7530T and
     * HEM-7600T, so only a checksum-valid block is written there.
     */
    public final boolean erasedAllowed;
    private final int imageLength;

    private ClockBlock(int writeAddress,int imageOffset,int length,int clockOffset,int checksumOffset,boolean swapped,boolean erasedAllowed,int imageLength) {
        this.writeAddress=writeAddress; this.imageOffset=imageOffset; this.length=length; this.clockOffset=clockOffset;
        this.checksumOffset=checksumOffset; this.swapped=swapped; this.erasedAllowed=erasedAllowed; this.imageLength=imageLength;
    }

    /** Exact resolved identities only (see {@link WriteAspectIndex#resolve(String)}); null without a layout. */
    public static ClockBlock forIdentity(String identity) {
        if(identity==null) return null;
        switch(identity) {
            // 16-byte legacy maps share the X7 block-3 layout: bytes 44..59, clock 52..57, checksum 58.
            case "HEM-7150T": case "HEM-7155T": case "HEM-7156T": case "HEM-7342T": case "HEM-7361T":
                return new ClockBlock(0x80,44,16,52,58,false,true,68);
            case "HEM-7380T1-EOSL": case "HEM-7380T1-EBK": return new ClockBlock(0x80,44,16,52,58,false,false,68);
            case "HEM-6232T": return new ClockBlock(0x2D0,44,16,52,58,true,true,68);
            case "HEM-7530T": return new ClockBlock(0x2D0,44,16,52,58,true,false,68);
            case "HEM-7600T": return new ClockBlock(0x29A,20,10,22,28,true,false,38);
            default: return null;
        }
    }

    /** Settings-image length the caller must have read before using this layout. */
    public int imageLength() { return imageLength; }

    /** Logical block bytes {@code image[imageOffset .. imageOffset+length)}. */
    public byte[] block(byte[] image) {
        requireImage(image);
        byte[] out=new byte[length];
        System.arraycopy(image,imageOffset,out,0,length);
        return out;
    }

    /** 8-bit additive sum of the block bytes before the checksum matches the checksum byte. */
    public boolean checksumValid(byte[] image) {
        requireImage(image);
        return sum(image)==(image[checksumOffset]&255);
    }

    /** Every block byte is FF. */
    public boolean erased(byte[] image) {
        requireImage(image);
        for(int i=imageOffset;i<imageOffset+length;i++) if(image[i]!=(byte)255) return false;
        return true;
    }

    /** The stored clock, or null when a field is out of range or the date does not exist. */
    public LocalDateTime clock(byte[] image) {
        requireImage(image);
        int year=2000+(image[clockOffset]&255), month=image[clockOffset+1]&255, day=image[clockOffset+2]&255;
        int hour=image[clockOffset+3]&255, minute=image[clockOffset+4]&255, second=image[clockOffset+5]&255;
        if(month<1 || month>12 || day<1 || day>31 || hour>23 || minute>59 || second>59) return null;
        try { return LocalDateTime.of(year,month,day,hour,minute,second); }
        catch(DateTimeException invalid) { return null; }
    }

    /** A copy of the block with the six clock bytes set and the checksum recomputed; other bytes unchanged. */
    public byte[] updated(byte[] image,LocalDateTime written) {
        requireImage(image);
        if(written==null || written.getYear()<2000 || written.getYear()>2255)
            throw new IllegalArgumentException("Clock time must be within 2000..2255");
        byte[] copy=image.clone();
        copy[clockOffset]=(byte)(written.getYear()-2000);
        copy[clockOffset+1]=(byte)written.getMonthValue();
        copy[clockOffset+2]=(byte)written.getDayOfMonth();
        copy[clockOffset+3]=(byte)written.getHour();
        copy[clockOffset+4]=(byte)written.getMinute();
        copy[clockOffset+5]=(byte)written.getSecond();
        copy[checksumOffset]=(byte)sum(copy);
        return block(copy);
    }

    private int sum(byte[] image) {
        int sum=0;
        for(int i=imageOffset;i<checksumOffset;i++) sum+=image[i]&255;
        return sum&255;
    }

    private void requireImage(byte[] image) {
        if(image==null || image.length!=imageLength)
            throw new IllegalArgumentException("Clock block requires exactly "+imageLength+" settings bytes");
    }
}
