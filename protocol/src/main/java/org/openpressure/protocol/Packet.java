package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;

final class Packet {
    final byte[] data;
    int position;
    Packet(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Missing packet");
        this.data = data;
    }
    int u8() { require(1); return data[position++] & 255; }
    int u16() { int lo = u8(); return lo | u8() << 8; }
    void require(int count) { if (position + count > data.length) throw new IllegalArgumentException("Truncated packet at " + position); }
    LocalDateTime dateTime() {
        int year = u16(), month = u8(), day = u8(), hour = u8(), minute = u8(), second = u8();
        if (hour > 23 || minute > 59 || second > 59 || month > 12 || day > 31 || year!=0 && (year<1582 || year>9999))
            throw new IllegalArgumentException("Invalid device timestamp");
        if (year == 0 || month == 0 || day == 0) return null;
        try { return LocalDateTime.of(year,month,day,hour,minute,second); }
        catch (DateTimeException ex) { throw new IllegalArgumentException("Invalid device timestamp", ex); }
    }
}
