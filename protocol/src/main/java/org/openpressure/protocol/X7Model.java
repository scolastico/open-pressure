package org.openpressure.protocol;

import java.util.Locale;

/**
 * Read-map facts of one exact single-channel X7-family identity (docs/protocol.md, "X7 model map"). Omron's
 * configurations for these identities share the 68-byte settings/index image at {@code 0x10}, the 16-byte record
 * layout starting at {@code 0x1C4} and two contiguous user banks; they differ only in slots per user. Pure data: it
 * sends nothing and authorizes no write. Write permissions stay in {@link WriteAspectIndex}.
 */
public final class X7Model {
    /** Settings/index image read before history. */
    public static final int SETTINGS_START=0x10, SETTINGS_BYTES=68;
    /** First record of user 1 and the size of one record. */
    public static final int RECORD_START=0x01c4, RECORD_BYTES=16, USERS=2;

    /** HEM-7380T1-EOSL (X7 Smart AFib): 100 slots per user, user 2 at {@code 0x804}. */
    public static final X7Model HEM_7380T1_EOSL=new X7Model("HEM-7380T1-EOSL","HEM-7380T1","X7 Smart AFib",100,false,0x00d4);
    /** HEM-7380T1-EBK: Omron's configuration is identical to EOSL apart from model_id. */
    public static final X7Model HEM_7380T1_EBK=new X7Model("HEM-7380T1-EBK","HEM-7380T1","X7 Smart AFib",100,false,0x00d3);
    /** Bare base identity: same read map as EOSL/EBK; it resolves to no write-aspect row. */
    public static final X7Model HEM_7380T1=new X7Model("HEM-7380T1","HEM-7380T1",null,100,false,-1);
    /** HEM-7196T1-FLE (M4 Connect AFib): 60 slots per user, user 2 at {@code 0x584}. Experimental, hardware-untested. */
    public static final X7Model HEM_7196T1_FLE=new X7Model("HEM-7196T1-FLE","HEM-7196T1","M4 Connect AFib",60,true,0x00eb);
    /** HEM-7196T1-FLEO (X4 Connect AFib): Omron's configuration is identical to FLE apart from model_id. */
    public static final X7Model HEM_7196T1_FLEO=new X7Model("HEM-7196T1-FLEO","HEM-7196T1","X4 Connect AFib",60,true,0x00e1);
    private static final X7Model[] MODELS={HEM_7380T1_EOSL,HEM_7380T1_EBK,HEM_7380T1,HEM_7196T1_FLE,HEM_7196T1_FLEO};

    /** Exact identity; the write-aspect index row when one exists. */
    public final String identity;
    /** Memory-map family label shown in device reports; identities of one family have identical configurations. */
    public final String mapName;
    /** Display name from the firmware or Omron's catalog; null for the bare base identity. Never an accepted identity. */
    public final String productName;
    public final int recordsPerUser;
    /**
     * Omron's VitalDataIndexes for this identity force the irregular-pulse and movement flags to 0 when the
     * record's AFib bit is set.
     */
    public final boolean afibMasksFlags;
    /** Omron catalog group ID for category 0 (blood pressure), or -1 when the identity has no catalog entry. */
    public final int advertisedModelId;

    private X7Model(String identity,String mapName,String productName,int recordsPerUser,boolean afibMasksFlags,int advertisedModelId) {
        this.identity=identity; this.mapName=mapName; this.productName=productName; this.recordsPerUser=recordsPerUser;
        this.afibMasksFlags=afibMasksFlags; this.advertisedModelId=advertisedModelId;
    }

    /**
     * Exact Device Information identity, NULs and surrounding whitespace removed. Model numbers match exactly; the
     * marketing name "X7 Smart AFib", which the tested EOSL firmware reports, matches ignoring case. Omron's catalog
     * names of the HEM-7196T1 ("M4 Connect AFib", "X4 Connect AFib") are display names only: no firmware is known to
     * report them, and M4 / X4 names cover other generations. Anything else, including a bare "HEM-7196T1",
     * returns null.
     */
    public static X7Model identify(String disModel) {
        if(disModel==null) return null;
        String model=disModel.replace("\u0000","").trim();
        for(X7Model candidate:MODELS) if(candidate.identity.equals(model)) return candidate;
        // "X7 Smart AFib" is what the EOSL firmware reports; EBK shares the map but has no distinct name.
        if(model.equalsIgnoreCase("X7 Smart AFib")) return HEM_7380T1_EOSL;
        return null;
    }

    /**
     * The X7-family identity an advertisement names, from a {@code BLEsmart_<category><model>} name or an encrypted
     * type 8/9 manufacturer payload (category 0 only), or null when neither carries a listed X7-family model ID.
     * The name wins when both do. Used only to refuse a session whose advertisement contradicts its Device
     * Information identity; it never selects a map.
     */
    public static X7Model advertised(String name,byte[] manufacturerPayload) {
        X7Model named=fromName(name);
        return named!=null?named:fromPayload(manufacturerPayload);
    }

    /**
     * Whether an advertisement contradicts this Device Information identity: its name or its type 8/9 payload names
     * a listed X7-family model of a different memory map. Advertisements without a listed X7-family ID never
     * contradict.
     */
    public boolean contradictedBy(String name,byte[] manufacturerPayload) {
        X7Model named=fromName(name), payload=fromPayload(manufacturerPayload);
        return named!=null && !named.mapName.equals(mapName) || payload!=null && !payload.mapName.equals(mapName);
    }

    private static X7Model fromName(String name) {
        if(name==null) return null;
        String value=name.replace("\u0000","").trim();
        if(value.length()<17 || !value.regionMatches(true,0,"BLEsmart_",0,9) || !value.substring(9,17).matches("[0-9A-Fa-f]{8}")) return null;
        int category=Integer.parseInt(value.substring(9,13),16), model=Integer.parseInt(value.substring(13,17),16);
        return category==0?byAdvertisedId(model):null;
    }

    private static X7Model fromPayload(byte[] payload) {
        if(payload==null || payload.length==0) return null;
        int type=payload[0]&255;
        if(type!=8 && type!=9) return null;
        try {
            OmronAdvertisement parsed=OmronAdvertisement.parseManufacturerPayload(payload);
            return parsed.category==0?byAdvertisedId(parsed.modelId):null;
        } catch(IllegalArgumentException malformed) { return null; }
    }

    private static X7Model byAdvertisedId(int id) {
        for(X7Model candidate:MODELS) if(candidate.advertisedModelId>=0 && candidate.advertisedModelId==id) return candidate;
        return null;
    }

    /** First byte of a user's bank (user 1 or 2). */
    public int bankStart(int user) {
        requireUser(user);
        return RECORD_START+(user-1)*bankBytes();
    }
    /** Bytes of one user's bank. */
    public int bankBytes() { return recordsPerUser*RECORD_BYTES; }
    /** Whether {@code [address, address+length)} lies wholly inside the settings image or one user's bank. */
    public boolean containsReadRange(int address,int length) {
        if(length<1 || address<0) return false;
        if(address>=SETTINGS_START && address+length<=SETTINGS_START+SETTINGS_BYTES) return true;
        for(int user=1;user<=USERS;user++) if(address>=bankStart(user) && address+length<=bankStart(user)+bankBytes()) return true;
        return false;
    }

    static void requireUser(int user) {
        if(user<1 || user>USERS) throw new IllegalArgumentException("X7 user must be 1 or 2");
    }

    @Override public String toString() {
        return String.format(Locale.ROOT,"%s (%d slots per user, user 2 at 0x%03X)",identity,recordsPerUser,bankStart(2));
    }
}
