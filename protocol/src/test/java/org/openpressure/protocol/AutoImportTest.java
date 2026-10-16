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
    private static OmronAdvertisement advertisement(String payload) { return OmronAdvertisement.parseManufacturerPayload(hex(payload)); }
    private static byte[] hex(String payload) { return java.util.HexFormat.of().parseHex(payload); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid advertisement accepted"); }
}
