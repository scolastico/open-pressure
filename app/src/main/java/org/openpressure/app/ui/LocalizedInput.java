package org.openpressure.app.ui;

import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.Locale;

/** Parses a single measurement value without accepting thousands separators. */
public final class LocalizedInput {
    private LocalizedInput() {}

    public static double number(String value, Locale locale) {
        String trimmed=value.trim();
        NumberFormat format=NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        ParsePosition position=new ParsePosition(0);
        Number parsed=format.parse(trimmed,position);
        if(parsed==null || position.getIndex()!=trimmed.length() || !Double.isFinite(parsed.doubleValue()))
            throw new IllegalArgumentException("Invalid measurement value");
        return parsed.doubleValue();
    }
}
