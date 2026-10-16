package org.openpressure.protocol;

/** Counts wire records separately from newly inserted and rejected readings. */
public final class HistoryTransfer {
    public enum Action { IGNORE, REPORT, COMPLETE, FAILED }
    private enum Phase { IDLE, COUNT, REPORT, FINISHED }
    private Phase phase=Phase.IDLE;
    public int expected=-1,received,rejected,added;
    public String failure="";
    public void begin() { phase=Phase.COUNT; expected=-1; received=0; rejected=0; added=0; failure=""; }
    public void measurement(boolean decoded,boolean inserted) {
        if(phase!=Phase.REPORT) return;
        received++; if(!decoded) rejected++; else if(inserted) added++;
    }
    public Action response(Commands.RacpResponse response) {
        if(phase==Phase.COUNT && response.opcode==5) {
            expected=response.count;
            if(expected==0) { phase=Phase.FINISHED; return Action.COMPLETE; }
            phase=Phase.REPORT; return Action.REPORT;
        }
        int request=phase==Phase.COUNT?4:phase==Phase.REPORT?1:0;
        if(request==0 || response.opcode!=6 || response.request!=request) return Action.IGNORE;
        if(response.result==6 && phase==Phase.COUNT) { expected=0; phase=Phase.FINISHED; return Action.COMPLETE; }
        if(response.result!=1) return fail("Monitor rejected transfer ("+response.result+")");
        if(phase==Phase.COUNT) return Action.IGNORE; // Wait for the actual count response.
        if(rejected>0 || received!=expected) return fail("Partial history: "+received+" of "+expected+" packets received, "+rejected+" rejected. Received readings were retained.");
        phase=Phase.FINISHED; return Action.COMPLETE;
    }
    private Action fail(String message) { phase=Phase.FINISHED; failure=message; return Action.FAILED; }
}
