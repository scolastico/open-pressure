package org.openpressure.omronsandbox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.protocol.VendorMemoryModel;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

/** Compares Omron's per-model memory maps with OpenPressure's; differences are reported, not failed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MemoryMapComparisonTest {
    private static final String HEM_7156T_AP="[model]\nmodel_id=HEM-7156T-AP\nconnect_type=WLS3.0\n\n[memory_map]\n"+
        "index_setting_read_address=10\nindex_num_of_datatypes=1\nindex_1_data_pointer_max=59\n"+
        "; measurement data of person no.1.\ndata_1_address=98\ndata_1_address_step=16\ndata_1_size=16\n\n[func_GETDATA1_command]\nattr=wait:5000,len:8\n";

    @Test public void parsesSyntheticDeviceConfig() {
        OmronConfig config=OmronConfig.parse(HEM_7156T_AP);
        assertEquals("WLS3.0",config.connectType);
        List<OmronConfig.Area> areas=config.areas();
        assertEquals(1,areas.size());
        OmronConfig.Area area=areas.get(0);
        assertEquals(1,area.user); assertEquals(0x98,area.start); assertEquals(16,area.step); assertEquals(16,area.size); assertEquals(60,area.count);
    }

    @Test public void hem7156tIdentities() throws Exception {
        OmronApk apk=OmronApk.assumePresent();
        StringBuilder report=new StringBuilder("Omron "+apk.version+" memory maps vs OpenPressure\n");
        int compared=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(apk.deviceCategoryZip))) {
            for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                String name=e.getName();
                if(!name.matches(".*/0x0000/HEM-7156T[^/]*/DeviceConfig\\.sys")) continue;
                String identifier=name.replaceAll(".*/0x0000/([^/]+)/DeviceConfig\\.sys","$1");
                OmronConfig config=OmronConfig.parse(new String(zip.readAllBytes(),StandardCharsets.UTF_8));
                report.append(OmronConfig.compare(identifier,config,VendorMemoryModel.identify("HEM-7156T"))).append('\n');
                compared++;
            }
        }
        assertTrue("HEM-7156T configs found",compared>=2);
        Path out=Paths.get("build","omron-sandbox","memory-map.txt");
        Files.createDirectories(out.getParent());
        Files.write(out,report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
    }
}
