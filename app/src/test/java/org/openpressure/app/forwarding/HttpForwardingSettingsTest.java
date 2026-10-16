package org.openpressure.app.forwarding;

import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class HttpForwardingSettingsTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private void save(String endpoint,String authorization,boolean local) {
        HttpForwardingSettings.save(context,endpoint,authorization,local);
    }
    @Test public void acceptsExplicitHttpAndHttpsDestinations() throws Exception {
        save("https://example.invalid/measurements?tenant=synthetic","Bearer synthetic-only",false);
        save("http://127.0.0.1:1234/measurements","Basic synthetic-only",true);
    }
    @Test public void rejectsUnsafeDestinationsAndHeaderInjection() throws Exception {
        for(String endpoint:new String[]{"ftp://example.invalid","https://user:password@example.invalid/a","https://example.invalid/#private","https:///missing-host","https://example.invalid/\r\ninjected","https://example.invalid:99999/","https://example.invalid/a b"}) {
            try { save(endpoint,"",true); fail("Accepted unsafe endpoint: "+endpoint); } catch(IllegalArgumentException expected) { }
        }
        for(String authorization:new String[]{"Bearer x\r\nX-Injected: yes","\u0000","Bearer\tx","Bearer \u00e4","Bearer \u007f"}) {
            try { save("https://example.invalid",authorization,true); fail("Accepted unsafe authorization"); } catch(IllegalArgumentException expected) { }
        }
    }
}
