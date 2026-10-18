package org.openpressure.protocol;

import java.util.Arrays;

/** Synthetic index images shaped like the connect fixtures; no device traces. */
public final class VendorMemoryIndexTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Vendor memory index tests passed: "+run()+" assertions"); }
    public static int run() {
        checks=0;
        populatedSlots();
        untrustedIndexes();
        swappedAndChecksummedImages();
        modelHelpers();
        return checks;
    }

    private static void populatedSlots() {
        // hem-7155t fixture index: both users position 1, unsent 1, sequence 1
        VendorMemoryIndex i=VendorMemoryIndex.parse(model("HEM-7155T"),image("HEM-7155T","0100010001000100 01000000 01000000"));
        check(i.position(1)==1 && i.unsent(2)==1 && i.sequence(2)==1,"prefix fields");
        check(i.trusted(1) && i.populated(1)==1,"one populated slot");
        check(i.populatedRanges(3).size()==2 && i.populatedRanges(3).get(1).start==0x458 && i.populatedRanges(3).get(1).length==16,"ranges per user");
        check(i.populatedRanges(1).size()==1 && i.populatedRanges(1).get(0).user==1,"user filter");
        // unsent bit 15 is parity, not part of the count
        check(VendorMemoryIndex.parse(model("HEM-7155T"),image("HEM-7155T","0100010001800100 01000000 01000000")).unsent(1)==1,"unsent parity bit masked");
        // wrapped bank: sequence 150 >= capacity 60 -> all 60 slots
        check(VendorMemoryIndex.parse(model("HEM-7156T"),image("HEM-7156T","0500000005000000 96000000 00000000")).populatedRanges(1).get(0).length==960,"wrapped bank reads every slot");
        // sequence 0 and position 0: trusted, nothing populated
        check(VendorMemoryIndex.parse(model("HEM-7150T"),image("HEM-7150T","0000000000000000 00000000 00000000")).populatedRanges(1).isEmpty(),"empty bank");
        // a missing user is never trusted and its bit is ignored
        VendorMemoryIndex one=VendorMemoryIndex.parse(model("HEM-7150T"),image("HEM-7150T","0100000001000000 01000000 00000000"));
        check(!one.trusted(2) && !one.trusted(0) && one.populatedRanges(3).size()==1,"missing user ignored");
    }

    private static void untrustedIndexes() {
        // mismatch position 2 / sequence 1: untrusted
        check(!VendorMemoryIndex.parse(model("HEM-7150T"),image("HEM-7150T","0200000001000000 01000000 00000000")).trusted(1),"position must equal sequence below capacity");
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7150T"),image("HEM-7150T","0200000001000000 01000000 00000000")).populatedRanges(1));
        // position above capacity: untrusted
        check(!VendorMemoryIndex.parse(model("HEM-7150T"),image("HEM-7150T","3d00000001000000 3d000000 00000000")).trusted(1),"pointer beyond capacity");
        // an untrusted unselected user does not block the selected one
        VendorMemoryIndex mixed=VendorMemoryIndex.parse(model("HEM-7155T"),image("HEM-7155T","0100020001000100 01000000 01000000"));
        check(!mixed.trusted(2) && mixed.populatedRanges(1).size()==1,"only selected users must be trusted");
        rejects(() -> mixed.populatedRanges(3));
    }

    private static void swappedAndChecksummedImages() {
        // 14-byte map: wire pairs swapped (hem-6232t fixture), same logical values
        VendorMemoryIndex w=VendorMemoryIndex.parse(model("HEM-6232T"),image("HEM-6232T","0001000100010001 00010000 00010000"));
        check(w.position(2)==1 && w.sequence(1)==1 && w.populatedRanges(2).get(0).start==0x860 && w.populatedRanges(2).get(0).length==14,"swapped index");
        // HEM-6232T has no block-3 check: an FF block passes
        byte[] wrist=image("HEM-6232T","0001000100010001 00010000 00010000"); wrist[50]=1;
        check(VendorMemoryIndex.parse(model("HEM-6232T"),wrist).trusted(1),"no block-3 check outside HEM-7530T");
        // HEM-7530T block-3 checksum: zero block passes, corrupted block fails
        check(VendorMemoryIndex.parse(model("HEM-7530T"),image("HEM-7530T","0001000000010000 00010000")).populated(1)==1,"checked block passes");
        rejects(() -> { byte[] bad=image("HEM-7530T","0001000000010000 00010000"); bad[50]=1; VendorMemoryIndex.parse(model("HEM-7530T"),bad); });
        // unsupported models and wrong lengths
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7600T"),new byte[38]));
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7322T"),new byte[38]));
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7377T1"),new byte[24]));
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7155T"),new byte[67]));
        rejects(() -> VendorMemoryIndex.parse(model("HEM-7155T"),null));
    }

    private static void modelHelpers() {
        check(model("HEM-7156T").capacity()==60 && model("HEM-7156T").historyRanges(2).get(0).user==1,"user 2 on a one-user model falls back to user 1");
        check(model("HEM-7377T1").sharedBank() && model("HEM-7377T1").historyRanges(1).size()==2,"shared-bank model ignores the filter");
        check(model("HEM-7342T").settingsRange().start==0x10 && model("HEM-7342T").settingsRange().length==68,"settings range");
        check(model("HEM-7156T").selectableUsers()==1 && model("HEM-7155T").selectableUsers()==2 && model("HEM-7156T").unconfirmedBank(2) && !model("HEM-7155T").unconfirmedBank(2),"user choice and unconfirmed bank");
        check(model("HEM-6232T").historyRanges(3).size()==2 && model("HEM-6232T").historyRanges(2).get(0).start==0x860 && model("HEM-6232T").historyRanges(0).get(0).user==1,"user bitmask");
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-6232T"})
            check(model(id).indexSupported(),"index-supported: "+id);
        for(String id:new String[]{"HEM-7322T","HEM-7377T1","HEM-7600T"})
            check(!model(id).indexSupported(),"no index evidence: "+id);
        VendorMemoryModel.Range r=VendorMemoryModel.range(0x98,32,1);
        check(r.start==0x98 && r.length==32 && r.user==1 && model("HEM-7155T").settingsRange().user==0,"range factory");
    }

    /** The prefix at offset 0 of an FF-filled settings image; HEM-7530T gets a zeroed block 3. */
    private static byte[] image(String id,String prefix) {
        VendorMemoryModel m=model(id);
        byte[] image=new byte[m.settingsLength]; Arrays.fill(image,(byte)255);
        byte[] p=hex(prefix.replace(" ",""));
        System.arraycopy(p,0,image,0,p.length);
        if(id.equals("HEM-7530T")) Arrays.fill(image,44,60,(byte)0);
        return image;
    }
    private static VendorMemoryModel model(String id) {
        VendorMemoryModel m=VendorMemoryModel.identify(id);
        if(m==null) throw new AssertionError("Expected supported model: "+id);
        return m;
    }
    private static byte[] hex(String text) { return java.util.HexFormat.of().parseHex(text); }
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable call) {
        boolean rejected=false; try { call.run(); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"untrusted index must be rejected");
    }
}
