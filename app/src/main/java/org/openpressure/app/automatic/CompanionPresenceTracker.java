package org.openpressure.app.automatic;

import java.util.HashMap;
import java.util.Map;

/** A disconnected GATT link must not revoke a still-nearby BLE monitor's lease. */
final class CompanionPresenceTracker {
    private final Map<String,Integer> sources=new HashMap<>();
    boolean update(String address,int source,boolean present) {
        int value=sources.getOrDefault(address,0);
        value=present?value|source:value&~source;
        if(value==0) sources.remove(address); else sources.put(address,value);
        return value!=0;
    }
    boolean update(String address,int source,boolean present,boolean authorized) {
        if(!authorized) { sources.remove(address); return false; }
        return update(address,source,present);
    }
    boolean present(String address) { return sources.containsKey(address); }
}
