package org.openpressure.app.ui;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class LocalizedInputTest {
    @Test public void acceptsLocaleDecimalsAndArabicDigits() {
        assertEquals(12.5,LocalizedInput.number("12,5",Locale.GERMANY),0.0001);
        assertEquals(12.5,LocalizedInput.number("12.5",Locale.US),0.0001);
        assertEquals(120,LocalizedInput.number(" ١٢٠ ",Locale.forLanguageTag("ar")),0.0001);
    }
    @Test public void wrongDecimalSeparatorCannotBecomeDifferentPressure() {
        assertThrows(IllegalArgumentException.class,() -> LocalizedInput.number("12.5",Locale.GERMANY));
        assertThrows(IllegalArgumentException.class,() -> LocalizedInput.number("8.0",Locale.GERMANY));
        assertThrows(IllegalArgumentException.class,() -> LocalizedInput.number("12,5",Locale.US));
        assertThrows(IllegalArgumentException.class,() -> LocalizedInput.number("8,0",Locale.US));
    }
    @Test public void rejectsEmptyNonFiniteAndTrailingText() {
        for(String input:new String[]{"","NaN","∞","120 mmHg","1,200"})
            assertThrows(IllegalArgumentException.class,() -> LocalizedInput.number(input,Locale.US));
    }
}
