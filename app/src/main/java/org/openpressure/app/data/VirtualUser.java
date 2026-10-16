package org.openpressure.app.data;

/** A stable local owner; independent of the physical monitor's user bank. */
public final class VirtualUser {
    public final int id;
    public final String name;
    public VirtualUser(int id,String name) { this.id=id; this.name=name; }
}
