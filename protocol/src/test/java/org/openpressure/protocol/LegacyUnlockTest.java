package org.openpressure.protocol;

import java.util.Arrays;
import java.util.HexFormat;

/** Exercises key programming and reconnect independently of Android and BLE. */
public final class LegacyUnlockTest {
    private static int checks;
    private static final byte[] KEY=hex("00112233445566778899aabbccddeeff");
    public static void main(String[] args) { System.out.println("Legacy unlock checks: "+run()); }
    public static int run() {
        checks=0;
        newlyProgrammedKeyMustUnlockBeforeReady();
        acknowledgmentsAreCheckedByFirstTwoBytesOnly();
        LegacyUnlock pair=LegacyUnlock.pair(KEY);
        check(!pair.allows(hex("0200000000000000000000000000000000")),"unissued pairing command has no permit");
        same("0200000000000000000000000000000000",pair.command());
        check(pair.allows(hex("0200000000000000000000000000000000")),"issued enter-pairing command has permit");
        check(!pair.allows(null) && !pair.allows(new byte[0]),"missing frame has no permit");
        rejects(pair::key);
        check(pair.accept(hex("8200")) && !pair.complete(),"entering pairing mode does not establish a key");
        check(!pair.allows(hex("0200000000000000000000000000000000")),"pairing-mode ACK revokes previous permit");
        check(!pair.allows(hex("0000112233445566778899aabbccddeeff")),"programming needs command issuance");
        same("0000112233445566778899aabbccddeeff",pair.command());
        check(pair.allows(hex("0000112233445566778899aabbccddeeff")),"only issued key programming frame permitted");
        check(!pair.allows(hex("0001112233445566778899aabbccddeeff")),"changed key never uses permit");
        check(pair.accept(hex("8000")) && !pair.complete(),"key programming ACK still requires unlock");
        check(!pair.allows(hex("0000112233445566778899aabbccddeeff")),"programming ACK revokes programming permit");
        same("0100112233445566778899aabbccddeeff",pair.command());
        check(pair.accept(hex("8100")) && pair.complete(),"newly programmed key unlocks before success");
        check(Arrays.equals(KEY,pair.key()),"acknowledged key available");
        byte[] supplied=KEY.clone(); LegacyUnlock reconnect=LegacyUnlock.connect(supplied); supplied[0]=99;
        same("0100112233445566778899aabbccddeeff",reconnect.command());
        check(reconnect.allows(hex("0100112233445566778899aabbccddeeff")),"reconnect permits issued unlock");
        check(!reconnect.allows(hex("0000112233445566778899aabbccddeeff")) && !reconnect.allows(hex("0200000000000000000000000000000000")),"reconnect cannot permit key programming");
        check(reconnect.accept(hex("8100")) && reconnect.complete(),"stored key unlock acknowledged");
        rejects(reconnect::command); rejects(() -> reconnect.accept(hex("8100")));
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[15],new byte[17]}) {
            rejects(() -> LegacyUnlock.pair(invalid)); rejects(() -> LegacyUnlock.connect(invalid));
        }
        for(String invalid:new String[]{"","8201","82010000","8100","81","80"}) {
            LegacyUnlock failed=LegacyUnlock.pair(KEY); failed.command();
            rejects(() -> failed.accept(hex(invalid))); rejects(failed::command); rejects(failed::key);
            check(!failed.allows(hex("0200000000000000000000000000000000")),"invalid acknowledgment revokes permit");
        }
        LegacyUnlock early=LegacyUnlock.pair(KEY); rejects(() -> early.accept(hex("8200"))); rejects(early::command);
        LegacyUnlock second=LegacyUnlock.pair(KEY); second.command(); second.accept(hex("8200")); second.command();
        rejects(() -> second.accept(hex("8200"))); rejects(second::key);
        LegacyUnlock wrong=LegacyUnlock.connect(KEY); wrong.command(); rejects(() -> wrong.accept(hex("8101")));
        LegacyUnlock closed=LegacyUnlock.connect(KEY); closed.close(); rejects(closed::command); rejects(closed::key);
        check(!closed.allows(hex("0100112233445566778899aabbccddeeff")),"closed helper has no permit");
        LegacyUnlock complete=LegacyUnlock.connect(KEY); complete.command(); complete.accept(hex("8100")); complete.close();
        rejects(complete::key); rejects(complete::command); check(!complete.complete(),"closed helper exposes no established key");
        return checks;
    }
    /** Programming the key and opening the authenticated read session are separate wire exchanges. */
    private static void newlyProgrammedKeyMustUnlockBeforeReady() {
        LegacyUnlock pair=LegacyUnlock.pair(hex("00112233445566778899aabbccddeeff"));
        same("0200000000000000000000000000000000",pair.command());
        pair.accept(hex("8200"));
        same("0000112233445566778899aabbccddeeff",pair.command());
        pair.accept(hex("8000"));
        check(!pair.complete(),"programming ACK cannot skip unlock before START");
        rejects(pair::key);
        check(!pair.allows(hex("0100112233445566778899aabbccddeeff")),"unlock permit requires issuance");
        same("0100112233445566778899aabbccddeeff",pair.command());
        check(pair.allows(hex("0100112233445566778899aabbccddeeff")),"pair final stage permits exact unlock");
        check(!pair.allows(hex("0000112233445566778899aabbccddeeff")) && !pair.allows(hex("0200000000000000000000000000000000")),"pair final stage cannot program again");
        check(pair.accept(hex("8100")) && pair.complete(),"unlock ACK establishes read readiness");
        check(Arrays.equals(KEY,pair.key()),"key available after final unlock ACK");
    }
    /** Omron's own app checks only acknowledgment byte 0; padding beyond byte 1 is ignored. */
    private static void acknowledgmentsAreCheckedByFirstTwoBytesOnly() {
        LegacyUnlock single=LegacyUnlock.connect(KEY); single.command();
        check(single.accept(hex("81")) && single.complete(),"one-byte unlock acknowledgment completes a connect");
        LegacyUnlock padded=LegacyUnlock.connect(KEY); padded.command();
        check(padded.accept(hex("810000ff")) && padded.complete(),"padded unlock acknowledgment completes a connect");
        LegacyUnlock negative=LegacyUnlock.connect(KEY); negative.command();
        rejects(() -> negative.accept(hex("8101"))); rejects(negative::key);
        LegacyUnlock paddedNegative=LegacyUnlock.connect(KEY); paddedNegative.command();
        rejects(() -> paddedNegative.accept(hex("810100"))); rejects(paddedNegative::key);
        LegacyUnlock wrongPhase=LegacyUnlock.connect(KEY); wrongPhase.command();
        rejects(() -> wrongPhase.accept(hex("80"))); rejects(wrongPhase::key);
        LegacyUnlock empty=LegacyUnlock.connect(KEY); empty.command();
        rejects(() -> empty.accept(new byte[0])); rejects(empty::command);
        LegacyUnlock pair=LegacyUnlock.pair(KEY); pair.command();
        check(pair.accept(hex("82")) && !pair.complete(),"one-byte pairing-mode acknowledgment advances");
        pair.command(); check(pair.accept(hex("8000aa")) && !pair.complete(),"padded programming acknowledgment advances");
        same("0100112233445566778899aabbccddeeff",pair.command());
        check(pair.accept(hex("810000")) && pair.complete(),"padded unlock acknowledgment completes pairing");
    }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void same(String s,byte[] v) { check(Arrays.equals(hex(s),v),"unexpected wire command"); }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid unlock accepted"); }
}
