package org.openpressure.protocol;

/** Fresh plain-WLP registration must not accept the previous Android bond as confirmation. */
public final class X7Bonding {
    public enum Bond { NONE, BONDING, BONDED }
    public enum Action { REMOVE_BOND, CREATE_BOND, WAIT, CONNECT, FAILED }
    private enum Phase { REMOVING, CREATING, BONDING, CLOSED }
    private Phase phase;

    public X7Bonding(Bond previous) {
        if(previous==null) throw new IllegalArgumentException("Missing Android bond state");
        phase=previous==Bond.NONE?Phase.CREATING:Phase.REMOVING;
    }
    public Action action() {
        return phase==Phase.REMOVING?Action.REMOVE_BOND:phase==Phase.CREATING?Action.CREATE_BOND:Action.WAIT;
    }
    public Action changed(Bond bond) {
        if(bond==null) throw new IllegalArgumentException("Missing Android bond state");
        if(phase==Phase.CLOSED) return Action.WAIT;
        if(phase==Phase.REMOVING) {
            if(bond!=Bond.NONE) return Action.WAIT;
            phase=Phase.CREATING;
            return Action.CREATE_BOND;
        }
        if(bond==Bond.BONDING) { phase=Phase.BONDING; return Action.WAIT; }
        // NONE can be the duplicate removal broadcast; BONDED can belong to the old attempt.
        if(phase==Phase.CREATING) return Action.WAIT;
        phase=Phase.CLOSED;
        return bond==Bond.BONDED?Action.CONNECT:Action.FAILED;
    }
    public void cancel() { phase=Phase.CLOSED; }
}
