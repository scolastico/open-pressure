package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public final class ProtocolTest {
    private static int checks;
    public static void main(String[] args) {
        checks+=AutoImportTest.run();
        near(120, BloodPressure.decodeSfloat(0x0078));
        near(-1, BloodPressure.decodeSfloat(0x0fff));
        near(12.3, BloodPressure.decodeSfloat(0xf07b));
        near(-12.3, BloodPressure.decodeSfloat(0xff85));
        for (int raw : new int[]{0x07ff,0x07fe,0x0800,0x0801,0x0802})
            check(Double.isNaN(BloodPressure.decodeSfloat(raw)), "SFLOAT unavailable " + raw);
        BloodPressure full = BloodPressure.parse(hex("1e 78 00 50 00 5d 00 ea 07 0a 10 0e 1e 2d 48 00 02 27 00"));
        near(120, full.systolic); near(80, full.diastolic); near(72, full.pulse);
        check(full.userId == 2, "bank identity");
        check(full.time.equals(LocalDateTime.of(2026,10,16,14,30,45)), "timestamp");
        check(full.status == 39 && full.statusLabels().size() == 4, "movement/cuff/irregular/position");
        check(!full.statusLabels().toString().contains("AFib"), "IHB must never imply AFib");
        BloodPressure minimal = BloodPressure.parse(hex("00 78 00 50 00 ff 07"));
        check(minimal.time == null && minimal.userId == null && minimal.pulse == null, "absent fields stay absent");
        check(Double.isNaN(minimal.meanArterialPressure), "missing MAP retained");
        BloodPressure kpa = BloodPressure.parse(hex("01 a0 f0 6b f0 78 f0"));
        near(120.00992, kpa.systolicMmHg());
        for (int n = 0; n < 19; n++) {
            final byte[] shortPacket = Arrays.copyOf(hex("1e 78 00 50 00 5d 00 ea 07 0a 10 0e 1e 2d 48 00 02 27 00"), n);
            rejects(() -> BloodPressure.parse(shortPacket));
        }
        rejects(() -> BloodPressure.parse(hex("20 78 00 50 00 5d 00")));
        rejects(() -> BloodPressure.parse(hex("00 ff 07 50 00 5d 00")));
        rejects(() -> BloodPressure.parse(hex("02 78 00 50 00 5d 00 ea 07 02 1e 0a 00 00")));
        rejects(() -> BloodPressure.parse(hex("02 78 00 50 00 5d 00 ff ff 01 01 00 00 00")));
        BloodPressure unknownTime = BloodPressure.parse(hex("02 78 00 50 00 5d 00 00 00 00 00 00 00 00"));
        check(unknownTime.time == null, "unknown device clock is not phone time");
        same("ea070a100e1e2d", Commands.dateTime(LocalDateTime.of(2026,10,16,14,30,45)));
        same("ea070a100e1e2d050001", Commands.currentTime(LocalDateTime.of(2026,10,16,14,30,45)));
        rejects(() -> Commands.dateTime(LocalDateTime.of(10000,1,1,0,0)));
        rejects(() -> Commands.dateTime(LocalDateTime.of(1500,1,1,0,0)));
        same("0401", Commands.countAll()); same("0101", Commands.reportAll());
        same("0103013412", Commands.reportFrom(0x1234));
        same("040301ffff", Commands.countFrom(65535)); same("1000", Commands.latestSequence());
        rejects(() -> Commands.reportFrom(-1)); rejects(() -> Commands.countFrom(65536));
        same("02020e02", Commands.consent(2,526));
        same("40010e02", Commands.registerUser(1,526));
        rejects(() -> Commands.consent(0,526)); rejects(() -> Commands.registerUser(1,10000));
        check(Commands.racp(hex("05000201")).count == 258, "RACP count little endian");
        check(Commands.racp(hex("06000101")).success(), "RACP completion");
        check(!Commands.racp(hex("06000108")).success(), "RACP failure not success");
        check(Commands.racp(hex("11003412")).sequence == 0x1234, "vendor latest sequence");
        rejects(() -> Commands.racp(hex("0500")));
        OmronAdvertisement adv = OmronAdvertisement.parseManufacturerPayload(hex("012d341205785603"));
        check(adv.users.size() == 2 && adv.pairingMode && adv.clockUnset && adv.standardMode, "MSD flags");
        check(adv.users.get(0).lastSequence == 0x1234 && adv.users.get(1).recordCount == 3, "MSD banks");
        rejects(() -> OmronAdvertisement.parseManufacturerPayload(hex("010100")));
        rejects(() -> OmronAdvertisement.parseManufacturerPayload(hex("0400000000")));
        for(String name:new String[]{"BLESmart_1234","BLEsmart_1234","blesmart_1234","BLeSmArT_1234"})
            check(OmronAdvertisement.hasKnownName(name),"Omron name discovery ignores letter case");
        for(String name:new String[]{null,"","BLESmart","BLESmart_","Other_BLESmart_1234"})
            check(!OmronAdvertisement.hasKnownName(name),"unrelated names are not monitor candidates");
        same("0801001000200039", Wlp.read(0x1000,32));
        rejects(() -> Wlp.read(0x1000000,16));
        check(Arrays.equals(hex("01020304"), Wlp.payload(hex("0c8100000000010203040089"),1)), "WLP validated payload");
        rejects(() -> Wlp.payload(hex("0c8100000000010203040088"),1));
        check(ProtocolProfile.select(true,true,true,false,false) == ProtocolProfile.STANDARD_BP, "standard optional profile precedes vendor UUID");
        check(ProtocolProfile.select(true,false,true,true,false) == ProtocolProfile.STP, "STP candidate without WLP data banks");
        check(ProtocolProfile.select(false,false,true,false,false) == ProtocolProfile.LEGACY_WLP, "vendor UUID alone is not STP");
        check(ProtocolProfile.select(false,false,false,false,false) == ProtocolProfile.UNSUPPORTED, "unknown profile");
        check(ProtocolProfile.select(true,false,true,true,true)==ProtocolProfile.LEGACY_WLP,"shared unlock UUID with WLP data channels must not trigger STP");
        check(ProtocolProfile.select(true,true,true,true,true)==ProtocolProfile.STANDARD_BP,"standard optional profile remains usable without any vendor writes");
        testX7TransactionPolicy();
        testX7Transaction();
        testX7Authentication();
        testX7SecureWritePolicy();
        testX7Security();
        testX7FreshBond();
        testX7MemoryIndex();
        checks+=X7RegistrationTest.run();
        checks+=X7ModelTest.run();
        checks+=VendorMemoryModelTest.run();
        checks+=VendorMemoryIndexTest.run();
        checks+=VendorWlpSessionTest.run();
        checks+=LegacyUnlockTest.run();
        checks+=DeviceReportTest.run();
        checks+=VendorWritePolicyTest.run();
        checks+=WriteAspectIndexTest.run();
        checks+=MonitorProductTest.run();
        checks+=ClockBlockTest.run();
        checks+=VendorClockWriteTest.run();
        checks+=WriteAspectSourceGuardTest.run();
        checks+=FixtureTest.run();
        checks+=FixtureReplayTest.run();
        testWlpAppendedRanges();
        testWlpConnectionLifecycle();
        testWritePolicy();
        testWlpReadSession();
        testX7Records();
        testHealthExport();
        List<String> events = new java.util.ArrayList<>();
        OperationQueue queue = new OperationQueue(events::add);
        queue.add("read:a", () -> { events.add("a"); return true; }, () -> events.add("done:a"));
        queue.add("write:b", () -> { events.add("b"); return true; }, () -> events.add("done:b"));
        check(events.equals(List.of("a")), "ATT operations must not overlap");
        queue.complete("write:b",0);
        check(events.equals(List.of("a")), "unrelated callbacks cannot release queue");
        queue.complete("read:a",0);
        check(events.equals(List.of("a","done:a","b")), "success releases next action");
        queue.complete("write:b",5);
        check(!events.contains("done:b") && !queue.busy(), "failed writes cannot report success");
        queue.add("read:c", () -> true, () -> events.add("bad"));
        queue.clear(); queue.complete("read:c",0);
        check(!events.contains("bad"), "callbacks after disconnect are ignored");
        queue.add("read:rejected", () -> false, () -> events.add("bad"));
        check(!queue.busy() && !events.contains("bad"), "rejected operations fail promptly");
        events.clear();
        queue.add("read:optional",() -> true,() -> events.add("bad"),error -> events.add("optional failed"));
        queue.add("write:required",() -> { events.add("required started"); return true; },() -> events.add("required done"));
        queue.complete("read:optional",2);
        check(events.equals(List.of("optional failed","required started")),"optional metadata failure must not discard required writes");
        queue.complete("write:required",0);
        check(events.contains("required done"),"connection remains usable after optional read failure");
        events.clear();
        int[] starts={0};
        queue.add("descriptor:held",() -> { starts[0]++; return true; },() -> events.add("held done"));
        queue.add("write:after",() -> { events.add("after started"); return true; },() -> events.add("after done"));
        check(!queue.restart("write:after") && starts[0]==1,"only the active operation can restart");
        check(queue.restart("descriptor:held") && starts[0]==2 && queue.busy() && !events.contains("after started"),"a held operation restarts in place and keeps the queue");
        queue.complete("descriptor:held",0);
        check(events.equals(List.of("held done","after started")),"the restarted operation completes once and releases the next");
        queue.complete("write:after",0);
        check(!queue.restart("write:after") && !queue.busy(),"nothing to restart when idle");
        events.clear();
        queue.add("descriptor:rejected",() -> starts[0]++==2,() -> events.add("bad"));
        check(queue.restart("descriptor:rejected") && !queue.busy() && events.size()==1 && events.get(0).startsWith("Android rejected"),"a rejected restart fails like a rejected start");
        events.clear();
        queue.add("read:required",() -> true,() -> events.add("bad"));
        queue.add("write:after-timeout",() -> { events.add("bad"); return true; },() -> events.add("bad"));
        queue.timeout("read:unrelated");
        check(queue.busy() && events.isEmpty(),"stale timeout cannot release a different operation");
        queue.timeout("read:required");
        check(events.equals(List.of("Bluetooth operation timed out: read:required")) && !queue.busy(),"required timeout still fails the connection and discards queued work");
        same("bb1d6929e95937287fa37d129b756746", Crypto.cmac(hex("2b7e151628aed2a6abf7158809cf4f3c"),new byte[0]));
        same("070a16b46b4d4144f79bdd9dd04a287c", Crypto.cmac(hex("2b7e151628aed2a6abf7158809cf4f3c"),hex("6bc1bee22e409f96e93d7e117393172a")));
        byte[] ccm = Crypto.seal(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"),hex("00000003020100a0a1a2a3a4a5"),hex("0001020304050607"),hex("08090a0b0c0d0e0f101112131415161718191a1b1c1d1e"));
        same("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0",ccm);
        same("08090a0b0c0d0e0f101112131415161718191a1b1c1d1e", Crypto.open(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"),hex("00000003020100a0a1a2a3a4a5"),hex("0001020304050607"),ccm));
        ccm[4]^=1;
        rejects(() -> Crypto.open(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"),hex("00000003020100a0a1a2a3a4a5"),hex("0001020304050607"),ccm));
        testSecureReconnect();
        testFirstPairing();
        HistoryTransfer transfer=new HistoryTransfer(); transfer.begin();
        check(transfer.response(Commands.racp(hex("05000200")))==HistoryTransfer.Action.REPORT,"positive count starts report");
        check(transfer.response(Commands.racp(hex("05000200")))==HistoryTransfer.Action.IGNORE,"duplicate count cannot start another report");
        transfer.measurement(true,true); transfer.measurement(false,false);
        check(transfer.response(Commands.racp(hex("06000101")))==HistoryTransfer.Action.FAILED,"RACP success with an undecoded reading is incomplete");
        check(transfer.rejected==1 && transfer.received==2,"all incoming readings accounted for");
        transfer.begin(); transfer.response(Commands.racp(hex("05000200"))); transfer.measurement(true,true);
        check(transfer.response(Commands.racp(hex("06000101")))==HistoryTransfer.Action.FAILED,"missing records cannot complete");
        transfer.begin(); transfer.response(Commands.racp(hex("05000200"))); transfer.measurement(true,true); transfer.measurement(true,false);
        check(transfer.response(Commands.racp(hex("06000101")))==HistoryTransfer.Action.COMPLETE && transfer.added==1,"saved duplicates count as received records");
        transfer.begin(); check(transfer.response(Commands.racp(hex("05000000")))==HistoryTransfer.Action.COMPLETE,"zero count completes honestly");
        transfer.begin(); transfer.response(Commands.racp(hex("05000100")));
        check(transfer.response(Commands.racp(hex("06000106")))==HistoryTransfer.Action.FAILED,"no records after positive count is incomplete");
        System.out.println("Protocol tests passed: " + checks + " assertions");
    }
    private static void testX7FreshBond() {
        // Reusing an existing bond would skip the fresh-registration confirmation exchange.
        X7Bonding bonded=new X7Bonding(X7Bonding.Bond.BONDED);
        check(bonded.action()==X7Bonding.Action.REMOVE_BOND,"Pair first removes the old plain-X7 bond");
        check(bonded.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.WAIT,"old BONDED cannot complete registration");
        check(bonded.changed(X7Bonding.Bond.NONE)==X7Bonding.Action.CREATE_BOND,"wait for unbond before new bonding");
        check(bonded.changed(X7Bonding.Bond.BONDING)==X7Bonding.Action.WAIT,"wait for actual confirmation");
        check(bonded.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.CONNECT,"only new bond completion permits GATT reconnect");
        check(bonded.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.WAIT,"duplicate broadcast cannot start another connection");
        X7Bonding unbonded=new X7Bonding(X7Bonding.Bond.NONE);
        check(unbonded.action()==X7Bonding.Action.CREATE_BOND,"unbonded Pair creates bond before GATT");
        check(unbonded.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.WAIT,"stale BONDED without a fresh BONDING cannot connect");
        check(unbonded.changed(X7Bonding.Bond.NONE)==X7Bonding.Action.WAIT,"duplicate NONE before bonding must not cancel the new attempt");
        check(unbonded.changed(X7Bonding.Bond.BONDING)==X7Bonding.Action.WAIT,"observe new bonding before accepting outcome");
        check(unbonded.changed(X7Bonding.Bond.NONE)==X7Bonding.Action.FAILED,"rejected bond cannot enable read mode");
        check(unbonded.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.WAIT,"late success after rejection cannot connect");
        X7Bonding inProgress=new X7Bonding(X7Bonding.Bond.BONDING);
        check(inProgress.action()==X7Bonding.Action.REMOVE_BOND,"fresh Pair does not reuse an in-progress old attempt");
        inProgress.cancel();
        check(inProgress.changed(X7Bonding.Bond.NONE)==X7Bonding.Action.WAIT,"cancel prevents automatic rebond");
        X7Bonding rejected=new X7Bonding(X7Bonding.Bond.BONDED);
        rejected.cancel();
        check(rejected.changed(X7Bonding.Bond.BONDED)==X7Bonding.Action.WAIT,"failed bond removal never falls back to old bond");
    }

    private static void testWlpConnectionLifecycle() {
        WlpConnection connection=new WlpConnection();
        check(!connection.shutdownExpected(),"transport readiness alone cannot make a later link loss successful");
        rejects(connection::finishTransfer);
        WlpReadSession read=connection.begin(0x10,68,16);
        rejects(() -> connection.begin(0x10,68,16));
        read.accept(hex("0880000000100098"));
        rejects(connection::finishTransfer);
        for(int offset=0;offset<68;offset+=16) read.accept(wlpReply(0x81,0x10+offset,Math.min(16,68-offset),new byte[Math.min(16,68-offset)]));
        check(!connection.shutdownExpected(),"all data bytes without END acknowledgement cannot establish successful shutdown");
        rejects(connection::finishTransfer);
        same("080f000000000007",read.command());
        check(!connection.shutdownExpected(),"sending END cannot establish successful shutdown");
        read.accept(hex("088f000000000087"));
        connection.finishTransfer();
        check(connection.shutdownExpected(),"validated END acknowledgement permits the cuff to close its connection");
        rejects(() -> connection.begin(0x10,68,16));
        check(connection.close(),"expected close retains the completed transfer outcome");
        rejects(connection::finishTransfer);
        rejects(() -> connection.begin(0x10,68,16));
        WlpConnection interrupted=new WlpConnection();
        WlpReadSession pending=interrupted.begin(0x10,16,16);
        pending.accept(hex("0880000000100098"));
        check(!interrupted.close() && !interrupted.shutdownExpected(),"a disconnect during an active read remains a failure/interruption");
        WlpConnection noEnd=new WlpConnection();
        WlpReadSession missingEnd=noEnd.begin(0x10,1,16);
        missingEnd.accept(hex("0880000000100098")); missingEnd.accept(wlpReply(0x81,0x10,1,new byte[1]));
        missingEnd.command();
        check(!noEnd.close(),"missing END response cannot be mistaken for intentional cuff shutdown");
        WlpReadSession held=new WlpReadSession(0x10,1,16);
        held.accept(hex("0880000000100098")); held.accept(wlpReply(0x81,0x10,1,new byte[1]));
        rejects(() -> held.accept(hex("088f000000000087")));
        check(!held.complete() && held.rangeComplete(),"unsolicited END while registration backup is pending cannot finish the link");
        same("080f000000000007",held.command());
        held.accept(hex("088f000000000087"));
        check(held.complete(),"an END reply is accepted only after the caller actually issues END");
    }
    private static void testX7MemoryIndex() {
        // Captured index and checksum block; personal-profile bytes 24..43 are cleared.
        byte[] captured=hex("63800b0000800b00780100000b0000008380010101800b800000000000000000000000000000000000000000d6a40100000000001a0a1010172f05000000000000000000");
        X7MemoryIndex index=X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,captured);
        check(index.count(1)==99 && index.count(2)==11,"captured next pointers select only populated records");
        List<X7MemoryIndex.Range> ranges=index.historyRanges();
        check(ranges.size()==2,"both populated banks have ranges");
        check(ranges.get(0).start==0x1c4 && ranges.get(0).length==1584 && ranges.get(0).user==1,"first bank ends at 0x7f4 before the unpopulated slot");
        check(ranges.get(1).start==0x804 && ranges.get(1).length==176 && ranges.get(1).user==2,"second bank reads only its eleven populated slots");
        check(index.historyRanges(3).size()==2 && index.selectedCount(3)==110,"both users selected");
        check(index.historyRanges(2).size()==1 && index.historyRanges(2).get(0).user==2 && index.selectedCount(2)==11,"user 2 only skips user 1's bank");
        check(index.historyRanges(1).size()==1 && index.historyRanges(1).get(0).user==1 && index.selectedCount(1)==99,"user 1 only");
        check(index.historyRanges(0).size()==1 && index.historyRanges(0).get(0).user==1,"empty selection falls back to user 1");
        captured[0]=0;
        check(index.count(1)==99,"later input changes cannot alter a planned read range");
        rejects(() -> index.count(0)); rejects(() -> index.count(3));
        for(int user=1;user<=2;user++) for(int pointer=0;pointer<256;pointer++) {
            byte[] settings=new byte[68]; settings[(user-1)*2]=(byte)pointer;
            final int selected=user;
            if(pointer<=100) {
                X7MemoryIndex parsed=X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,settings);
                check(parsed.count(selected)==pointer,"nonfull count applies original minus-one latest-position correction");
                check(parsed.historyRanges().size()==(pointer==0?0:1),"empty banks never produce a memory request");
                settings[(user-1)*2+1]=0x40;
                check(X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,settings).count(selected)==100,"full flag exposes all one hundred slots including pointer zero");
            } else rejects(() -> X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,settings));
        }
        byte[] parityFlag=new byte[68]; parityFlag[1]=(byte)0x80;
        check(X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,parityFlag).count(1)==0,"parity bit must not be mistaken for the full flag");
        byte[] valid=hex("63800b0000800b00780100000b0000008380010101800b800000000000000000000000000000000000000000d6a40100000000001a0a1010172f05000000000000000000");
        for(int position:new int[]{44,57,58}) {
            byte[] corrupt=valid.clone(); corrupt[position]^=1;
            rejects(() -> X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,corrupt));
        }
        byte[] ignoredChecksum=valid.clone(); ignoredChecksum[24]^=1; ignoredChecksum[34]^=1; ignoredChecksum[59]=(byte)0xff;
        check(X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,ignoredChecksum).count(2)==11,"original model skips checksum blocks one and two and ignores the second checksum byte");
        rejects(() -> X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,null));
        for(int length=0;length<=69;length++) if(length!=68) {
            byte[] wrong=Arrays.copyOf(valid,length);
            rejects(() -> X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,wrong));
        }
    }
    private static void testWlpAppendedRanges() {
        WlpReadSession read=new WlpReadSession(0x10,3,2);
        check(!read.rangeComplete(),"range cannot complete before start acknowledgement");
        rejects(() -> read.appendRange(0x1c4,2));
        same("0800000000100018",read.command());
        read.accept(wlpReply(0x80,0,0,new byte[0]));
        rejects(() -> read.appendRange(0x1c4,2));
        read.accept(wlpReply(0x81,0x10,2,hex("0102")));
        check(!read.rangeComplete(),"partially read range cannot be extended");
        rejects(() -> read.appendRange(0x1c4,2));
        read.accept(wlpReply(0x81,0x12,1,hex("03")));
        check(read.rangeComplete() && !read.complete(),"finished range pauses before sending the only session end");
        rejects(() -> read.appendRange(-1,1)); rejects(() -> read.appendRange(65536,1));
        rejects(() -> read.appendRange(65535,2)); rejects(() -> read.appendRange(0,0));
        rejects(() -> read.appendRange(0,Integer.MAX_VALUE));
        read.appendRange(0x1c4,2);
        check(!read.rangeComplete() && read.received()==3,"continuation keeps cumulative bytes while starting a new range");
        same("08010001c40200ce",read.command());
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),java.util.UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),read.command());
        read.accept(wlpReply(0x81,0x1c4,2,hex("0405")));
        same("0102030405",read.data());
        check(read.rangeComplete() && read.received()==5,"all segments retain validated bytes");
        same("080f000000000007",read.command());
        rejects(() -> read.appendRange(0x804,1));
        read.accept(wlpReply(0x8f,0,0,new byte[0]));
        check(read.complete() && !read.rangeComplete(),"one end acknowledgement completes every appended range");
        rejects(() -> read.appendRange(0x804,1));
        WlpReadSession fragmented=new WlpReadSession(0,1,1);
        check(!fragmented.accept(hex("088000")),"partial start remains pending");
        rejects(() -> fragmented.appendRange(10,1));
        fragmented.accept(hex("0000000088"));
        check(!fragmented.accept(hex("098100")),"partial data remains pending");
        rejects(() -> fragmented.appendRange(10,1));
        fragmented.accept(hex("000001010088"));
        check(fragmented.rangeComplete(),"fragmented data may finish a range");
        same("080f000000000007",fragmented.command());
        check(!fragmented.accept(hex("088f00")),"partial end remains pending");
        rejects(() -> fragmented.appendRange(10,1));
        WlpReadSession bounded=new WlpReadSession(0,65535,16);
        bounded.accept(wlpReply(0x80,0,0,new byte[0]));
        for(int offset=0;offset<65535;offset+=16) {
            int length=Math.min(16,65535-offset);
            bounded.accept(wlpReply(0x81,offset,length,new byte[length]));
        }
        bounded.appendRange(65535,1); bounded.accept(wlpReply(0x81,65535,1,new byte[1]));
        check(bounded.received()==65536 && bounded.rangeComplete(),"aggregate read limit includes all segments");
        rejects(() -> bounded.appendRange(0,1));
    }
    private static void testX7Security() {
        check(X7Security.fromManufacturerPayload(null)==X7Security.Mode.UNKNOWN,"absent manufacturer data cannot select X7 encryption");
        check(X7Security.fromManufacturerPayload(new byte[0])==X7Security.Mode.UNKNOWN,"empty manufacturer data cannot select X7 encryption");
        for(int type=0;type<256;type++) {
            X7Security.Mode expected;
            switch(type) {
                case 1: case 2: case 3: case 6: expected=X7Security.Mode.PLAIN; break;
                case 8: case 9: expected=X7Security.Mode.ENCRYPTED; break;
                default: expected=X7Security.Mode.UNKNOWN;
            }
            check(X7Security.fromManufacturerPayload(new byte[]{(byte)type})==expected,"only recognized Omron advertisement types select security mode");
        }
        check(X7Security.fromManufacturerPayload(hex("08080000d40000000000"))==X7Security.Mode.ENCRYPTED,"Android company-stripped type is at byte zero");
        check(X7Security.fromManufacturerPayload(hex("0e0208080000d40000000000"))==X7Security.Mode.UNKNOWN,"company-prefixed raw advertising data is not misread as a mode");
    }
    private static void testX7Authentication() {
        X7Authentication authentication=new X7Authentication();
        same("2001000000000000000000000000000000000000",authentication.command());
        byte[] mutation=authentication.command(); mutation[0]=0x34;
        same("2001000000000000000000000000000000000000",authentication.command());
        check(!authentication.complete(),"encrypted transport is not yet authorized by the physical user");
        check(authentication.accept(null)==X7Authentication.Result.IGNORED,"missing authentication response ignored");
        check(authentication.accept(new byte[0])==X7Authentication.Result.IGNORED,"empty authentication response ignored");
        check(authentication.accept(hex("91000102030400"))==X7Authentication.Result.IGNORED,"transaction response cannot authorize a physical user");
        check(authentication.accept(hex("a002"))==X7Authentication.Result.IGNORED,"other authentication subcommands ignored");
        for(String shortReply:new String[]{"a0","a001","a00100"}) rejects(() -> authentication.accept(hex(shortReply)));
        check(authentication.accept(hex("a0010000"))==X7Authentication.Result.WAITING && !authentication.complete(),"zero means physical authorization still pending");
        for(int status=1;status<256;status++) {
            byte[] bad=hex("a0010001"); bad[2]=(byte)status;
            rejects(() -> authentication.accept(bad));
        }
        for(int state=3;state<256;state++) {
            byte[] bad=hex("a0010000"); bad[3]=(byte)state;
            rejects(() -> authentication.accept(bad));
        }
        check(!authentication.complete(),"failed authentication responses cannot authorize a physical user");
        byte[] padded=Arrays.copyOf(hex("a0010001"),244); Arrays.fill(padded,4,padded.length,(byte)0xff);
        check(authentication.accept(padded)==X7Authentication.Result.COMPLETE && authentication.complete(),"successful authorization ignores reserved MTU padding");
        check(authentication.accept(hex("a0010002"))==X7Authentication.Result.REJECTED && !authentication.complete(),"physical rejection is distinct from waiting and success");
    }
    private static void testX7SecureWritePolicy() {
        ProtocolProfile secure=ProtocolProfile.X7_SECURE_WLP;
        check(secure.isX7() && ProtocolProfile.X7_WLP.isX7(),"both X7 security modes use the X7 memory map");
        check(!ProtocolProfile.LEGACY_WLP.isX7() && !ProtocolProfile.STP.isX7(),"other profiles cannot inherit the X7 write allowlist");
        java.util.UUID service=uuid("fe4a"), cccd=uuid("2902");
        java.util.UUID system=java.util.UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b");
        java.util.UUID tx=java.util.UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b");
        java.util.UUID rx=java.util.UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b");
        byte[] authentication=hex("2001000000000000000000000000000000000000");
        DeviceWritePolicy.validateCharacteristic(secure,service,system,authentication);
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,authentication));
        for(int position=0;position<20;position++) {
            byte[] bad=authentication.clone(); bad[position]^=1;
            rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,bad));
        }
        for(int length:new int[]{0,1,3,19,21,244}) {
            byte[] bad=Arrays.copyOf(authentication,length);
            rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,bad));
        }
        for(int length:new int[]{17,20,46,89}) for(int opcode=0;opcode<256;opcode++) {
            byte[] handshake=new byte[length]; handshake[0]=0x70; handshake[1]=(byte)opcode;
            if(length==89 && opcode==1 || length==46 && (opcode==5 || opcode==6)) {
                DeviceWritePolicy.validateCharacteristic(secure,service,system,handshake);
            } else rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,handshake));
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,handshake));
        }
        DeviceWritePolicy.validateCharacteristic(secure,service,system,hex("1112345678000000000000000000000000000000"));
        for(int length:new int[]{17,20}) for(int opcode=0;opcode<256;opcode++) {
            byte[] command=new byte[length]; command[0]=(byte)opcode;
            if(length==20 && opcode==0x11) DeviceWritePolicy.validateCharacteristic(secure,service,system,command);
            else rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,command));
        }
        rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,null));
        for(byte[] read:new byte[][]{hex("0800000000100018"),hex("0801000010100009"),hex("080f000000000007")}) {
            DeviceWritePolicy.validateCharacteristic(secure,service,tx,read);
            rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,system,read));
        }
        rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,tx,hex("0901c0005401ff0062")));
        for(java.util.UUID channel:new java.util.UUID[]{system,tx,rx}) {
            rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,service,channel,hex("c0010000000102030405060708090a0b0c")));
        }
        for(java.util.UUID channel:new java.util.UUID[]{system,rx}) {
            DeviceWritePolicy.validateDescriptor(secure,service,channel,cccd,hex("0100"));
            rejects(() -> DeviceWritePolicy.validateDescriptor(secure,service,channel,cccd,hex("0200")));
        }
        rejects(() -> DeviceWritePolicy.validateDescriptor(secure,service,tx,cccd,hex("0100")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(secure,service,java.util.UUID.fromString("8858eb40-aee8-11e1-bb67-0002a5d5c51b"),cccd,hex("0100")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,uuid("1810"),system,authentication));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(secure,java.util.UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"),system,authentication));
    }
    private static void testX7Transaction() {
        byte[] nonce=hex("12345678");
        X7Transaction transaction=new X7Transaction(nonce);
        check(!transaction.ready(),"X7 cannot become ready before the transaction response");
        same("1112345678000000000000000000000000000000",transaction.request());
        nonce[0]=0;
        byte[] changedRequest=transaction.request(); changedRequest[1]=0;
        same("1112345678000000000000000000000000000000",transaction.request());
        check(!transaction.accept(null) && !transaction.accept(new byte[0]),"absent system notification cannot complete transaction");
        for(int opcode=0;opcode<256;opcode++) if(opcode!=0x91) {
            check(!transaction.accept(new byte[]{(byte)opcode}),"unrelated system notifications are ignored");
        }
        check(!transaction.ready(),"unrelated system traffic leaves transaction pending");
        byte[] reply=hex("91001234567800");
        for(int length=1;length<7;length++) {
            byte[] truncated=Arrays.copyOf(reply,length);
            rejects(() -> transaction.accept(truncated));
            check(!transaction.ready(),"truncated transaction reply cannot set ready");
        }
        for(int position=1;position<7;position++) {
            byte[] bad=reply.clone(); bad[position]^=1;
            rejects(() -> transaction.accept(bad));
            check(!transaction.ready(),"status, nonce and final flag are mandatory before ready");
        }
        check(transaction.accept(reply) && transaction.ready(),"only the matching successful response makes X7 ready");
        check(transaction.accept(reply) && transaction.ready(),"a duplicate matching response is harmless");
        // Captured from HEM-7380T1-EOSL: seven-byte response header padded to 244 bytes.
        byte[] capturedReply=Arrays.copyOf(hex("9100a132e74900"),244);
        X7Transaction captured=new X7Transaction(hex("a132e749"));
        check(captured.accept(capturedReply) && captured.ready(),"real X7 padded notification must complete the transaction");
        for(int position=1;position<7;position++) {
            X7Transaction rejected=new X7Transaction(hex("a132e749"));
            byte[] bad=capturedReply.clone(); bad[position]^=1;
            rejects(() -> rejected.accept(bad));
            check(!rejected.ready(),"padded reply still requires matching status, nonce and final flag");
        }
        for(int length:new int[]{8,20,21,240,244,512}) {
            X7Transaction padded=new X7Transaction(hex("12345678"));
            byte[] paddedReply=Arrays.copyOf(reply,length); Arrays.fill(paddedReply,7,length,(byte)0xff);
            check(padded.accept(paddedReply) && padded.ready(),"reserved trailing response bytes are ignored like the original guard");
        }
        rejects(() -> new X7Transaction(null));
        for(int length=0;length<=8;length++) if(length!=4) {
            final int invalidLength=length;
            rejects(() -> new X7Transaction(new byte[invalidLength]));
        }
    }
    private static void testX7TransactionPolicy() {
        java.util.UUID service=uuid("fe4a"), cccd=uuid("2902");
        java.util.UUID system=java.util.UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b");
        byte[] request=hex("11 12 34 56 78 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,request);
        DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,service,system,cccd,hex("0100"));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,service,system,cccd,hex("0200")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,service,system,cccd,hex("0000")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,service,system,uuid("2901"),hex("0100")));
        for(int length:new int[]{17,20}) for(int opcode=0;opcode<256;opcode++) {
            byte[] command=new byte[length]; command[0]=(byte)opcode;
            if(length==20 && opcode==0x11) DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,command);
            else rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,command));
        }
        for(int length=0;length<=40;length++) {
            if(length==20) continue;
            byte[] command=Arrays.copyOf(request,length);
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,command));
        }
        for(int reserved=5;reserved<20;reserved++) {
            byte[] command=request.clone(); command[reserved]=1;
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,command));
        }
        byte[] arbitraryNonce=hex("11 ff 00 80 7f 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00");
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,arbitraryNonce);
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,system,null));
        for(ProtocolProfile profile:new ProtocolProfile[]{ProtocolProfile.LEGACY_WLP,ProtocolProfile.STP,ProtocolProfile.STANDARD_BP,ProtocolProfile.UNSUPPORTED}) {
            rejects(() -> DeviceWritePolicy.validateCharacteristic(profile,service,system,request));
        }
        for(java.util.UUID wrongService:new java.util.UUID[]{uuid("1810"),java.util.UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b")}) {
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,wrongService,system,request));
            rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,wrongService,system,cccd,hex("0100")));
        }
        check(true,"X7 system allowlist permits only the bounded transaction and notification subscription");
    }
    private static void testWritePolicy() {
        java.util.UUID bp=uuid("1810"), optional=java.util.UUID.fromString("5df5e817-a945-4f81-89c0-3d4e9759c07c"), racp=uuid("2a52"), uds=uuid("181c"), ucp=uuid("2a9f");
        java.util.UUID vendor=uuid("fe4a"), legacy=java.util.UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b");
        java.util.UUID unlock=java.util.UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b");
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,optional,racp,hex("0401"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,optional,racp,hex("0103013412"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("02010e02"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("40020e02"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uuid("1805"),uuid("2a2b"),hex("ea070a100e1e2d050001"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,uuid("5045"),uuid("2a08"),hex("ea070a100e1e2d"));
        byte[] pair=new byte[89]; pair[0]=0x70; pair[1]=1;
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,vendor,unlock,pair);
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.LEGACY_WLP,vendor,unlock,pair));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,vendor,unlock,pair));
        byte[] eepromWrite=hex("0901c0005401ff0062");
        for(java.util.UUID service:new java.util.UUID[]{legacy,vendor}) {
            for(String channel:new String[]{"db5b55e0-aee7-11e1-965e-0002a5d5c51b","e0b8a060-aee7-11e1-92f4-0002a5d5c51b","0ae12b00-aee8-11e1-a192-0002a5d5c51b","10e1ba60-aee8-11e1-89e5-0002a5d5c51b"})
                rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,service,java.util.UUID.fromString(channel),eepromWrite));
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,service,unlock,eepromWrite));
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,service,unlock,hex("0200000000000000000000000000000000")));
        }
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,optional,racp,hex("0201")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("03")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("02010e02ff")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("40000e02")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uds,ucp,hex("40011027")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,optional,racp,eepromWrite));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uuid("1805"),uuid("2a2b"),new byte[10]));
        for(String invalidDate:new String[]{"01000101000000","ffff0101000000","00000101000000","ea07021e000000"}) {
            byte[] date=hex(invalidDate);
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,uuid("5045"),uuid("2a08"),date));
            byte[] currentTime=Arrays.copyOf(date,10); currentTime[7]=1; currentTime[9]=1;
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uuid("1805"),uuid("2a2b"),currentTime));
        }
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,uuid("180a"),uuid("2a08"),hex("ea070a100e1e2d")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STP,vendor,unlock,new byte[]{0x70,1}));
        for(int opcode=0;opcode<256;opcode++) {
            if(opcode==1||opcode==4) continue;
            byte[] command={(byte)opcode,1};
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.STANDARD_BP,optional,racp,command));
        }
        DeviceWritePolicy.validateDescriptor(ProtocolProfile.STANDARD_BP,bp,uuid("2a35"),uuid("2902"),hex("0200"));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.STANDARD_BP,bp,uuid("2a35"),uuid("2902"),hex("0300")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.STANDARD_BP,bp,uuid("2a35"),uuid("2901"),hex("0200")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.LEGACY_WLP,vendor,unlock,uuid("2902"),hex("0100")));
        check(true,"documented control writes accepted and EEPROM/data-bank writes rejected");
    }
    private static void testWlpReadSession() {
        WlpReadSession read=new WlpReadSession(0x1c4,20,16);
        same("0800000000100018",read.command());
        check(read.accept(wlpReply(0x80,0,0,new byte[0])) && read.received()==0,"session starts before reading");
        same("08010001c41000dc",read.command());
        byte[] block=new byte[16]; for(int i=0;i<block.length;i++) block[i]=(byte)i;
        byte[] reply=wlpReply(0x81,0x1c4,16,block);
        check(!read.accept(Arrays.copyOf(reply,13)),"partial notification cannot advance transfer");
        check(read.received()==0,"incomplete bytes are not accepted as EEPROM data");
        check(read.accept(Arrays.copyOfRange(reply,13,reply.length)),"fragmented reply reassembled");
        check(read.received()==16,"read progress counts validated bytes");
        same("08010001d40400d8",read.command());
        read.accept(wlpReply(0x81,0x1d4,4,hex("10111213")));
        same("080f000000000007",read.command());
        check(!read.complete(),"all bytes without end acknowledgement is incomplete");
        read.accept(wlpReply(0x8f,0,0,new byte[0]));
        check(read.complete(),"end acknowledgement completes transfer");
        same("000102030405060708090a0b0c0d0e0f10111213",read.data());
        rejects(read::command);
        rejects(() -> new WlpReadSession(-1,1,16));
        rejects(() -> new WlpReadSession(65535,2,16));
        rejects(() -> new WlpReadSession(0,65537,16));
        rejects(() -> new WlpReadSession(0,0,16));
        rejects(() -> new WlpReadSession(0,16,17));
        WlpReadSession edge=new WlpReadSession(65535,1,8); edge.accept(wlpReply(0x80,0,0,new byte[0]));
        check((edge.command()[2]&255)==0 && (edge.command()[3]&255)==255 && (edge.command()[4]&255)==255,"16bit boundary read never sets write flags");
        for(int fault=0;fault<6;fault++) {
            WlpReadSession invalid=new WlpReadSession(0x1c4,16,16); invalid.accept(wlpReply(0x80,0,0,new byte[0]));
            byte[] bad=wlpReply(0x81,0x1c4,16,new byte[16]);
            if(fault==0) bad[3]++;
            if(fault==1) bad[5]=15;
            if(fault==2) bad[2]=(byte)0xc0;
            if(fault==3) bad[bad.length-2]=1;
            if(fault==4) bad[1]=(byte)0x80;
            byte checksum=0; for(int i=0;i<bad.length-1;i++) checksum^=bad[i]; bad[bad.length-1]=checksum;
            if(fault==5) bad[bad.length-1]^=1;
            rejects(() -> invalid.accept(bad));
            check(invalid.received()==0,"bad response cannot contribute dump data");
        }
        WlpReadSession missing=new WlpReadSession(0,16,16); missing.accept(wlpReply(0x80,0,0,new byte[0]));
        rejects(() -> missing.accept(wlpReply(0x81,0,16,new byte[0])));
        java.util.UUID tx=java.util.UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b");
        java.util.UUID rx=java.util.UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b");
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,Wlp.read(0,16));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,hex("0800000000100018"));
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,hex("080f000000000007"));
        DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,uuid("fe4a"),rx,uuid("2902"),hex("0100"));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,uuid("2902"),hex("0100")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,hex("0901c0005401ff0062")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),rx,Wlp.read(0,16)));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("1805"),uuid("2a2b"),hex("ea070a100e1e2d050001")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,Wlp.read(65535,16)));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,Wlp.read(0x10000,16)));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,Wlp.read(0,17)));
        for(int flags=1;flags<256;flags++) {
            byte[] command=Wlp.read(0,16); command[2]=(byte)flags; command[7]^=(byte)flags;
            rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,command));
        }
        WlpReadSession full=new WlpReadSession(0,65536,16);
        full.accept(wlpReply(0x80,0x1234,0x10,new byte[0]));
        int requests=0;
        while(full.received()<65536) {
            byte[] command=full.command();
            DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,command);
            int address=((command[3]&255)<<8)|(command[4]&255), length=command[5]&255;
            if(command[1]!=1 || command[2]!=0 || address!=full.received() || length!=16) throw new AssertionError("unsafe or skipped read in full range");
            full.accept(wlpReply(0x81,address,length,new byte[length])); requests++;
        }
        check(requests==4096 && !full.complete(),"full address space uses only bounded reads and still requires end acknowledgement");
        DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,uuid("fe4a"),tx,full.command());
        byte[] endError=wlpReply(0x8f,0,0,new byte[0]); endError[6]=1; endError[7]^=1;
        rejects(() -> full.accept(endError));
        check(full.received()==65536 && !full.complete(),"end error preserves bytes without claiming complete");
    }
    private static byte[] wlpReply(int opcode,int address,int length,byte[] data) {
        byte[] reply=new byte[8+data.length]; reply[0]=(byte)reply.length; reply[1]=(byte)opcode;
        reply[2]=(byte)(address>>16); reply[3]=(byte)(address>>8); reply[4]=(byte)address; reply[5]=(byte)length;
        System.arraycopy(data,0,reply,6,data.length);
        for(int i=0;i<reply.length-1;i++) reply[reply.length-1]^=reply[i];
        return reply;
    }
    private static void testX7Records() {
        check(X7Record.supportsModel("HEM-7380T1-EOSL\u0000"),"exact X7 model identified");
        check(X7Record.supportsModel("HEM-7380T1"),"base model identity accepted");
        check(X7Record.supportsModel("HEM-7380T1-EBK"),"EBK uses the identical original X7 configuration");
        check(!X7Record.supportsModel("HEM-7381T1-AZ") && !X7Record.supportsModel("HEM-7377T1-ZAZ"),"same-generation models need their own map evidence");
        check(X7Record.supportsModel("X7 Smart Afib"),"actual EOSL firmware marketing-name identity accepted");
        check(!X7Record.supportsModel("X7 Smart") && !X7Record.supportsModel("X7 Smart Afib Unknown"),"only the confirmed marketing alias selects X7 map");
        check(!X7Record.supportsModel("HEM-7361T") && !X7Record.supportsModel("Other HEM-7380T1"),"hints cannot select a model-specific memory map");
        byte[] raw=X7ModelTest.summed(hex("5f50481a0e2aad070000341200000000"));
        X7Record r=X7Record.parse(raw,2,X7Model.HEM_7380T1_EOSL);
        near(120,r.measurement.systolic); near(80,r.measurement.diastolic); near(72,r.measurement.pulse);
        check(r.sequence==0x1234 && r.measurement.userId==2,"record sequence and bank preserved");
        check(r.measurement.time.equals(LocalDateTime.of(2026,10,16,14,30,45)),"original APK little-endian packed timestamp");
        check(Double.isNaN(r.measurement.meanArterialPressure),"MAP is absent, not calculated");
        byte[] empty=new byte[16]; Arrays.fill(empty,(byte)255);
        check(X7Record.parse(empty,1,X7Model.HEM_7380T1_EOSL)==null,"erased slot skipped");
        rejects(() -> X7Record.parse(new byte[15],1,X7Model.HEM_7380T1_EOSL));
        rejects(() -> X7Record.parse(raw,0,X7Model.HEM_7380T1_EOSL));
        rejects(() -> X7Record.parse(new byte[16],1,X7Model.HEM_7380T1_EOSL));
        byte[] noTime=raw.clone(); noTime[6]=(byte)0xbf; noTime=X7ModelTest.summed(noTime);
        check(X7Record.parse(noTime,1,X7Model.HEM_7380T1_EOSL).measurement.time==null,"invalid63seconds retained as unknown without clamping");
        byte[] year=raw.clone(); year[3]=100; year=X7ModelTest.summed(year);
        check(X7Record.parse(year,1,X7Model.HEM_7380T1_EOSL).measurement.time.getYear()==2100,"year uses full original byte, not six bits");
        byte[] flagged=raw.clone(); flagged[5]|=(byte)0xc0; flagged[7]|=0x20; flagged=X7ModelTest.summed(flagged);
        BloodPressure flags=X7Record.parse(flagged,1,X7Model.HEM_7380T1_EOSL).measurement;
        check(flags.status==5 && Boolean.TRUE.equals(flags.atrialFibrillation),"independent movement, IHB and actual AFib bit from original map");
        check(Boolean.FALSE.equals(r.measurement.atrialFibrillation),"clear AFib bit retained without inferring it from IHB");
        check(BloodPressure.parse(hex("00780050005d00")).atrialFibrillation==null,"standard SIG readings cannot infer AFib");
        byte[] ihb=raw.clone(); ihb[5]|=0x40; ihb=X7ModelTest.summed(ihb);
        check(Boolean.FALSE.equals(X7Record.parse(ihb,1,X7Model.HEM_7380T1_EOSL).measurement.atrialFibrillation),"IHB-only record does not assert AFib");
    }
    private static java.util.UUID uuid(String shortUuid) { return java.util.UUID.fromString("0000"+shortUuid+"-0000-1000-8000-00805f9b34fb"); }
    private static void testHealthExport() {
        java.time.ZoneId virtualZone=java.time.ZoneId.of("UTC");
        java.time.Instant virtualNow=java.time.Instant.parse("2026-10-17T12:00:00Z");
        HealthExport.Payload virtual=HealthExport.prepareVirtual("virtual-record",7,7,"2026-10-16T10:00:00",120,80,"mmHg",virtualZone,virtualNow);
        check(virtual!=null && virtual.systolicMmHg==120,"named profile export preserves pressure");
        rejects(() -> HealthExport.prepareVirtual("id",8,7,"2026-10-16T10:00",120,80,"mmHg",virtualZone,virtualNow));
        rejects(() -> HealthExport.prepareVirtual("id",null,7,"2026-10-16T10:00",120,80,"mmHg",virtualZone,virtualNow));
        rejects(() -> HealthExport.prepareVirtual("id",0,0,"2026-10-16T10:00",120,80,"mmHg",virtualZone,virtualNow));
        rejects(() -> HealthExport.prepareVirtual("id",7,7,null,120,80,"mmHg",virtualZone,virtualNow));
        java.time.ZoneId zone=java.time.ZoneId.of("Europe/Berlin");
        java.time.Instant now=java.time.Instant.parse("2026-10-16T18:00:00Z");
        HealthExport.Payload p=HealthExport.prepare("stable-record",1,1,"2026-10-16T14:30:45",120,80,"mmHg",zone,now);
        check(p.time.equals(java.time.Instant.parse("2026-10-16T12:30:45Z")) && p.offset.getTotalSeconds()==7200,"local device time converted with explicit zone");
        near(120,p.systolicMmHg); near(80,p.diastolicMmHg);
        check(p.clientRecordId.startsWith("open-pressure-bp-") && p.clientRecordId.length()==81,"stable opaque client ID");
        HealthExport.Payload same=HealthExport.prepare("stable-record",1,1,"2026-10-16T14:30:45",120,80,"mmHg",zone,now);
        check(p.clientRecordId.equals(same.clientRecordId),"retry identity is deterministic");
        HealthExport.Payload other=HealthExport.prepare("other-record",1,1,"2026-10-16T14:30:45",120,80,"mmHg",zone,now);
        check(!p.clientRecordId.equals(other.clientRecordId),"different local identities do not collide");
        HealthExport.Payload kpa=HealthExport.prepare("kpa",2,2,"2026-10-16T14:30:45",16,10.7,"kPa",zone,now);
        near(120.00992,kpa.systolicMmHg); near(80.256634,kpa.diastolicMmHg);
        rejects(() -> HealthExport.prepare("id",2,1,"2026-10-16T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",null,1,"2026-10-16T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",255,1,"2026-10-16T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,0,"2026-10-16T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("",1,1,"2026-10-16T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,null,120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-02-30T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-03-29T02:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-17T14:30",120,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",Double.NaN,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",301,80,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",120,0,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",120,201,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",80,120,"mmHg",zone,now));
        rejects(() -> HealthExport.prepare("id",1,1,"2026-10-16T14:30",120,80,"unknown",zone,now));
        HealthExport.Payload overlap=HealthExport.prepare("overlap",1,1,"2026-10-25T02:30",120,80,"mmHg",zone,java.time.Instant.parse("2026-11-01T00:00:00Z"));
        check(overlap.offset.getTotalSeconds()==7200,"DST overlap uses earlier valid offset without shifting wall time");
    }
    private static void testFirstPairing() {
        SecureSession session=new SecureSession(null);
        byte[] request=session.begin();
        check(request.length==89 && request[0]==0x70 && request[1]==1,"first pairing sends public key and random values");
        byte[] gx=hex("96c298d84539a1f4a033eb2d817d0377f240a463e5e6bcf847422ce1f2d1176b");
        byte[] gy=hex("f551bf376840b6cbce5e316b5733ce2b169e0f7c4aebe78e9b7f1afee242e34f");
        byte[] peer=new byte[89]; peer[0]=(byte)0xf0; peer[1]=(byte)0x81;
        Arrays.fill(peer,2,9,(byte)0xa1); Arrays.fill(peer,9,25,(byte)0xb2);
        System.arraycopy(gx,0,peer,25,32); System.arraycopy(gy,0,peer,57,32);
        byte[] encryption=session.accept(peer);
        check(encryption.length==46 && encryption[1]==5,"pair response advances to encryption");
        // Peer private scalar = 1, so the ECDH shared X coordinate equals the phone's public X.
        byte[] shared=new byte[32]; for(int i=0;i<32;i++) shared[i]=request[56-i];
        byte[] kdf=new byte[53]; kdf[0]=1; kdf[1]='b'; kdf[2]='t'; kdf[3]='l'; kdf[4]='e';
        System.arraycopy(request,9,kdf,5,16); Arrays.fill(kdf,21,37,(byte)0xb2);
        System.arraycopy(request,2,kdf,37,7); Arrays.fill(kdf,44,51,(byte)0xa1); kdf[51]=1;
        byte[] ltk=Crypto.cmac(Crypto.cmac(hex("6c888391aaf5a53860370bdb5a6083be"),shared),kdf);
        byte[] encReply=new byte[46]; encReply[0]=(byte)0xf0; encReply[1]=(byte)0x85;
        Arrays.fill(encReply,2,18,(byte)7); Arrays.fill(encReply,18,26,(byte)8); Arrays.fill(encReply,26,30,(byte)9);
        byte[] challenge=session.accept(encReply);
        byte[] skd=new byte[16]; Arrays.fill(skd,0,8,(byte)8); System.arraycopy(encryption,18,skd,8,8);
        byte[] key=Crypto.cmac(ltk,skd),nonce=new byte[13]; nonce[4]=(byte)0x80; Arrays.fill(nonce,5,9,(byte)9); System.arraycopy(encryption,26,nonce,9,4);
        byte[] opened=Crypto.open(key,nonce,new byte[4],Arrays.copyOfRange(challenge,2,46));
        for(int i=0;i<16;i++) check(opened[i]==7,"peer challenge authenticated using ECDH-derived LTK");
        nonce[4]=0; byte[] plain=new byte[36]; System.arraycopy(encryption,2,plain,0,16);
        byte[] finish=new byte[46]; finish[0]=(byte)0xf0; finish[1]=(byte)0x86;
        System.arraycopy(Crypto.seal(key,nonce,new byte[4],plain),0,finish,2,44);
        check(session.accept(finish)==null && Arrays.equals(ltk,session.longTermKey()),"first pairing persists the derived key only after challenge confirmation");
        session.close();
        SecureSession wrong=new SecureSession(null); wrong.begin(); Arrays.fill(peer,25,89,(byte)0);
        rejects(() -> wrong.accept(peer)); check(!wrong.established(),"invalid peer point fails closed");
    }
    private static void testSecureReconnect() {
        java.security.SecureRandom deterministic=new java.security.SecureRandom() {
            private int call;
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes,(byte)++call); }
        };
        SecureSession session=new SecureSession(hex("000102030405060708090a0b0c0d0e0f"),deterministic);
        byte[] start=session.begin();
        same("70050101010101010101010101010101010102020202020202020303030300000000000000000000000000000000",start);
        byte[] response=new byte[46]; response[0]=(byte)0xf0; response[1]=(byte)0x85;
        Arrays.fill(response,2,18,(byte)4); Arrays.fill(response,18,26,(byte)5); Arrays.fill(response,26,30,(byte)6);
        byte[] challenge=session.accept(response);
        check(challenge.length==46 && challenge[0]==0x70 && challenge[1]==6, "challenge framing");
        byte[] sessionKey=Crypto.cmac(hex("000102030405060708090a0b0c0d0e0f"),hex("05050505050505050202020202020202"));
        byte[] phoneNonce=hex("00000000800606060603030303");
        byte[] plain=Crypto.open(sessionKey,phoneNonce,new byte[4],Arrays.copyOfRange(challenge,2,46));
        same("040404040404040404040404040404040000000000000000000000000000000004040404",plain);
        byte[] devicePlain=new byte[36]; Arrays.fill(devicePlain,0,16,(byte)1);
        byte[] deviceReply=new byte[46]; deviceReply[0]=(byte)0xf0; deviceReply[1]=(byte)0x86;
        System.arraycopy(Crypto.seal(sessionKey,hex("00000000000606060603030303"),new byte[4],devicePlain),0,deviceReply,2,44);
        check(session.accept(deviceReply)==null && session.established(), "only authenticated challenge completes pairing");
        same("000102030405060708090a0b0c0d0e0f",session.longTermKey());
        byte[] encrypted=session.encrypt(hex("102030"));
        check(encrypted.length==16 && (encrypted[0]&255)==0xc0 && encrypted[1]==1, "encrypted data counter/header");
        same("102030",Crypto.open(sessionKey,hex("01000000800606060603030303"),hex("01000000"),Arrays.copyOfRange(encrypted,5,encrypted.length)));
        byte[] incoming=new byte[16]; incoming[0]=(byte)0xc0; incoming[1]=1;
        System.arraycopy(Crypto.seal(sessionKey,hex("01000000000606060603030303"),hex("01000000"),hex("aabbcc")),0,incoming,5,11);
        same("aabbcc",session.decrypt(incoming));
        rejects(() -> session.decrypt(incoming));
        session.close(); rejects(() -> session.encrypt(new byte[]{1}));
        SecureSession invalid=new SecureSession(new byte[16],deterministic); invalid.begin();
        rejects(() -> invalid.accept(hex("ff26")));
        check(!invalid.established(), "pairing rejection leaves session unauthenticated");
    }
    private static byte[] hex(String text) { return java.util.HexFormat.of().parseHex(text.replace(" ","")); }
    private static void same(String want, byte[] got) { check(want.equals(java.util.HexFormat.of().formatHex(got)), "expected " + want + ", got " + java.util.HexFormat.of().formatHex(got)); }
    private static void near(double want, double got) { check(Math.abs(want-got) < 0.0001, "expected " + want + ", got " + got); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) { boolean rejected = false; try { action.run(); } catch (IllegalArgumentException ex) { rejected = true; } check(rejected,"malformed input must be rejected"); }
}
