package org.openpressure.protocol;

import java.util.Arrays;

public final class AutoImportTest {
    private static int checks;
    public static int run() {
        checks=0;
        capturedAdvertisement();
        revisionIgnoresTransientFlagsAndErrorTrailer();
        successfulImportsCheckpointAndPermitNewRevisions();
        failuresAndCancellationRetryAfterCooldown();
        staleAttemptsCannotCompleteNewImports();
        invalidAdvertisementsNeverClaimAnImport();
        typesWithoutRecordCountsNeverClaimAnImport();
        automaticImportsRequireAVerifiedReadMap();
        forcedAttemptsIgnoreCheckpointAndCooldown();
        return checks;
    }
    private static void capturedAdvertisement() {
        OmronAdvertisement a=advertisement("06097801630b000b00030001");
        check(a.users.size()==2 && a.pairingMode,"captured type6 two-user pairing flags");
        check(a.users.get(0).lastSequence==376 && a.users.get(0).recordCount==99,"captured first bank");
        check(a.users.get(1).lastSequence==11 && a.users.get(1).recordCount==11,"captured second bank");
        OmronAdvertisement type2=advertisement("0200780163");
        check(type2.users.size()==1 && type2.users.get(0).lastSequence==376,"type2 shares sequence layout");
        for(int n=0;n<8;n++) {
            final byte[] shortPacket=Arrays.copyOf(hex("06017801630b000b"),n);
            rejects(() -> OmronAdvertisement.parseManufacturerPayload(shortPacket));
        }
        rejects(() -> advertisement("0400780163"));
        rejects(() -> OmronAdvertisement.parseManufacturerPayload(null));
    }
    private static void revisionIgnoresTransientFlagsAndErrorTrailer() {
        OmronAdvertisement pairing=advertisement("06097801630b000b00030001");
        OmronAdvertisement transfer=advertisement("06017801630b000bff33ff44");
        check(pairing.revision().equals(transfer.revision()),"pairing flags and error trailer must not create revisions");
        check(transfer.revision().equals(advertisement("02017801630b000b").revision()),"revision describes banks rather than transport type");
        check(!transfer.revision().equals(advertisement("06017901630b000b").revision()),"new first-user sequence changes revision");
        check(!transfer.revision().equals(advertisement("06017801630c000b").revision()),"new second-user sequence changes revision");
        check(!transfer.revision().equals(advertisement("06017801640b000b").revision()),"record count changes revision");
        check(!transfer.revision().equals(advertisement("0600780163").revision()),"bank count changes revision");
        OmronAdvertisement modes=advertisement("0650780163");
        check(modes.streamingMode && modes.forcedTransfer,"streaming and forced transfer bits exposed");
        check(!transfer.streamingMode && !transfer.forcedTransfer,"absent transfer bits clear");
    }
    private static void successfulImportsCheckpointAndPermitNewRevisions() {
        AutoImportTracker tracker=new AutoImportTracker();
        OmronAdvertisement original=advertisement("06017801640b0064");
        AutoImportTracker.Attempt first=tracker.offer("A",original,100);
        check(first!=null && tracker.busy(),"new stored measurements claim one import");
        check(first.address.equals("A") && first.revision.equals(original.revision()),"attempt captures device and bank revision");
        check(tracker.offer("A",original,101)==null,"repeated advertisement cannot claim twice");
        check(tracker.offer("B",original,101)==null,"another device waits for active import");
        check(tracker.completedRevision("A")==null,"offering does not checkpoint");
        check(tracker.finish(first,true,102) && !tracker.busy(),"success releases import");
        check(original.revision().equals(tracker.completedRevision("A")),"only success checkpoints revision");
        check(tracker.offer("A",original,103)==null,"same completed revision does not reconnect");
        AutoImportTracker.Attempt newFirst=tracker.offer("A",advertisement("06017901640b0064"),103);
        check(newFirst!=null,"full count still triggers changed first-user sequence");
        tracker.finish(newFirst,true,104);
        AutoImportTracker.Attempt newSecond=tracker.offer("A",advertisement("06017901640c0064"),105);
        check(newSecond!=null,"full count still triggers changed second-user sequence");
        tracker.finish(newSecond,true,106);
        AutoImportTracker.Attempt reset=tracker.offer("A",advertisement("0601010001010001"),107);
        check(reset!=null,"sequence reset remains a changed revision");
        tracker.finish(reset,true,108);
        check(tracker.offer("B",original,108)!=null,"successful device leaves no global cooldown");
        AutoImportTracker restored=new AutoImportTracker();
        restored.restore("A",original.revision());
        check(restored.offer("A",original,0)==null,"restored completion prevents duplicate import after restart");
        OmronAdvertisement forced=advertisement("06417801640b0064");
        check(restored.offer("A",forced,1)==null,"persistent forced flag cannot cause an import loop");
    }
    private static void failuresAndCancellationRetryAfterCooldown() {
        AutoImportTracker tracker=new AutoImportTracker();
        OmronAdvertisement a=advertisement("0600780163");
        AutoImportTracker.Attempt attempt=tracker.offer("A",a,0);
        check(tracker.finish(attempt,false,10),"failed current attempt finishes");
        check(tracker.completedRevision("A")==null,"failed attempt never checkpoints");
        check(tracker.offer("A",a,60009)==null,"failed device waits the whole minute");
        AutoImportTracker.Attempt other=tracker.offer("B",a,11);
        check(other!=null,"failure cooldown is per device");
        tracker.finish(other,true,12);
        AutoImportTracker.Attempt retry=tracker.offer("A",a,60010);
        check(retry!=null,"failed revision retries exactly at one minute");
        tracker.cancel(60011);
        check(!tracker.busy() && tracker.completedRevision("A")==null,"cancellation releases without checkpoint");
        check(tracker.offer("A",a,120010)==null,"cancellation enforces retry cooldown");
        check(tracker.offer("A",a,120011)!=null,"cancelled revision retries after cooldown");
    }
    private static void staleAttemptsCannotCompleteNewImports() {
        AutoImportTracker tracker=new AutoImportTracker();
        OmronAdvertisement a=advertisement("0600780163");
        AutoImportTracker.Attempt old=tracker.offer("A",a,0);
        tracker.cancel(1);
        AutoImportTracker.Attempt current=tracker.offer("A",a,60001);
        check(old.token!=current.token,"attempts receive unique identities");
        check(!tracker.finish(old,true,60002),"stale callback cannot complete retried revision");
        check(tracker.busy() && tracker.completedRevision("A")==null,"stale callback leaves active import and checkpoint untouched");
        check(!tracker.finish(null,true,60002),"absent callback token is stale");
        check(tracker.finish(current,true,60003),"current callback can finish");
        check(!tracker.finish(current,true,60004),"duplicate callback is stale");
    }
    private static void invalidAdvertisementsNeverClaimAnImport() {
        AutoImportTracker tracker=new AutoImportTracker();
        check(tracker.offer("A",advertisement("0608780163"),0)==null,"pairing advertisement waits for normal transfer");
        check(tracker.offer("A",advertisement("0610780163"),0)==null,"streaming advertisement cannot import history");
        check(tracker.offer("A",advertisement("0600000063"),0)==null,"zero sequence is empty despite count");
        check(tracker.offer("A",advertisement("0600780100"),0)==null,"zero record count has no records");
        check(tracker.offer("A",advertisement("0601000001630000"),0)==null,"sequence and count must describe the same nonempty bank");
        check(!tracker.busy(),"ineligible advertisements leave tracker idle");
        check(tracker.offer("A",advertisement("0601000000010001"),0)!=null,"one nonempty bank is sufficient");
    }
    private static void typesWithoutRecordCountsNeverClaimAnImport() {
        OmronAdvertisement type8=advertisement("08410007340278010100000000");
        check(type8.type==8 && type8.category==7 && type8.modelId==0x0234,"type8 category and little-endian model");
        check(type8.users.size()==2 && type8.users.get(0).lastSequence==376 && type8.users.get(1).lastSequence==1,"type8 sequences start after model");
        check(type8.users.get(0).recordCount==-1 && type8.forcedTransfer && !type8.hasRecordCounts(),"type8 reports no record count");
        check(type8.revision()==null,"type8 cannot form a checkpoint revision");
        OmronAdvertisement oneUser=advertisement("08000000341278010000");
        check(oneUser.users.size()==1 && oneUser.modelId==0x1234,"type8 single-user exact length");
        OmronAdvertisement type9=advertisement("09020000003412780109000b00");
        check(type9.users.size()==3 && type9.users.get(2).lastSequence==11,"type9 has one extra byte before category");
        check(type9.category==0 && type9.modelId==0x1234,"type9 category and model");
        rejects(() -> advertisement("0800000000341278"));
        rejects(() -> advertisement("080000000034127801000000"));
        rejects(() -> advertisement("0802000000341278010900"));
        rejects(() -> advertisement("0900000000341278"));
        rejects(() -> advertisement("0900000000341278010000"));
        OmronAdvertisement type3=advertisement("033c07");
        check(type3.users.isEmpty() && type3.pairingMode && type3.clockUnset,"type3 exposes only common flags");
        check(!type3.streamingMode && !type3.standardMode,"type3 guidance bits are not streaming or standard mode");
        rejects(() -> advertisement("0300"));
        AutoImportTracker tracker=new AutoImportTracker();
        check(tracker.offer("A",type8,0)==null && tracker.offer("A",type9,0)==null,"encrypted advertisements cannot claim a counted import");
        check(tracker.offer("A",type3,0)==null && !tracker.busy(),"type3 cannot claim an import");
    }
    private static void forcedAttemptsIgnoreCheckpointAndCooldown() {
        AutoImportTracker tracker=new AutoImportTracker();
        OmronAdvertisement a=advertisement("06017801640b0064");
        tracker.restore("A",a.revision());
        check(tracker.offer("A",a,0)==null,"completed revision blocks an offered import");
        AutoImportTracker.Attempt forced=tracker.force("A",a,0);
        check(forced!=null && tracker.busy() && forced.revision.equals(a.revision()),"a forced attempt ignores the completed revision");
        check(tracker.force("B",a,0)==null && tracker.offer("B",a,0)==null,"a forced attempt is exclusive");
        check(tracker.force("A",a,0)==null,"a forced attempt cannot be claimed twice");
        check(tracker.finish(forced,false,10) && !tracker.busy(),"a forced attempt finishes like an offered one");
        check(a.revision().equals(tracker.completedRevision("A")),"a failed forced attempt keeps the earlier checkpoint");
        OmronAdvertisement changed=advertisement("06017901640b0064");
        check(tracker.offer("A",changed,20)==null,"a failed forced attempt starts the automatic cooldown");
        AutoImportTracker.Attempt retry=tracker.force("A",changed,20);
        check(retry!=null,"a forced attempt ignores the cooldown");
        check(!tracker.finish(forced,true,21),"a stale forced attempt cannot finish the next one");
        check(tracker.finish(retry,true,22) && changed.revision().equals(tracker.completedRevision("A")),"a validated forced import checkpoints its revision");
        check(tracker.offer("A",changed,60030)==null,"the forced checkpoint suppresses the same automatic revision");
        AutoImportTracker.Attempt cancelled=tracker.force("A",changed,23);
        tracker.cancel(24);
        check(!tracker.busy() && !tracker.finish(cancelled,true,25),"cancelling releases a forced attempt");
        check(tracker.force("A",advertisement("0608780163"),26)==null && tracker.force("A",advertisement("0610780163"),26)==null,"pairing and streaming advertisements are never forced");
        check(tracker.force("A",advertisement("0600780100"),26)==null && tracker.force("A",advertisement("08410007340278010100000000"),26)==null,"empty or uncounted summaries are never forced");
        check(tracker.force("",a,26)==null && tracker.force(null,a,26)==null && tracker.force("A",null,26)==null,"a forced attempt needs a device and advertisement");
    }
    private static void automaticImportsRequireAVerifiedReadMap() {
        check(AutoImportTracker.supportsModel("HEM-7380T1-EOSL") && AutoImportTracker.supportsModel("HEM-7380T1-EBK"),"X7 identities are eligible");
        check(AutoImportTracker.supportsModel("HEM-7155T-EBK") && AutoImportTracker.supportsModel("HEM-7377T1-ZAZ"),"verified vendor maps are eligible");
        check(!AutoImportTracker.supportsModel("HEM-7155T_K4-EBK") && !AutoImportTracker.supportsModel("HEM-7376T1-Z"),"unmapped variants are not eligible");
        check(!AutoImportTracker.supportsModel(null) && !AutoImportTracker.supportsModel("BLEsmart_00000000"),"names and missing identities are not eligible");
    }
    private static OmronAdvertisement advertisement(String payload) { return OmronAdvertisement.parseManufacturerPayload(hex(payload)); }
    private static byte[] hex(String payload) { return java.util.HexFormat.of().parseHex(payload); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid advertisement accepted"); }
}
