package org.openpressure.app.data;

import org.junit.Test;
import org.openpressure.protocol.*;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.Assert.*;

public class RegistrationWritePolicyTest {
    private final UUID service=UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb");
    private final UUID tx=UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b");
    @Test public void registrationPermitCannotAuthorizeOtherWritesOrBypassReadOnlyEntryPoint() {
        X7Registration registration=new X7Registration("X7 Smart AFib",new byte[68],1,LocalDateTime.of(2026,10,16,12,0,0));
        registration.authorize(); byte[] first=registration.command();
        assertThrows(IllegalArgumentException.class,() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,service,tx,first));
        DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,first,registration);
        assertThrows(IllegalArgumentException.class,() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_SECURE_WLP,service,tx,first,registration));
        assertThrows(IllegalArgumentException.class,() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,tx,service,first,registration));
        byte[] modified=first.clone(); modified[6]^=1;
        assertThrows(IllegalArgumentException.class,() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,modified,registration));
        registration.cancel();
        assertThrows(IllegalArgumentException.class,() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,first,registration));
    }
}
