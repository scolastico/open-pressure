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
    /** Invented configuration in Omron's INI shape; no Omron text is committed. */
    private static final String SYNTHETIC="[model]\nmodel_id=HEM-0000T-X\nconnect_type=WLS3.0\n\n[memory_map]\n"+
        "index_num_of_datatypes=1\nindex_1_data_pointer_max=59\n; synthetic area\ndata_1_address=98\ndata_1_address_step=16\ndata_1_size=16\n\n[other]\nkey=value\n";

    @Test public void parsesSyntheticDeviceConfig() {
        OmronConfig config=OmronConfig.parse(SYNTHETIC);
        assertEquals("WLS3.0",config.connectType);
        List<OmronConfig.Area> areas=config.areas();
        assertEquals(1,areas.size());
        OmronConfig.Area area=areas.get(0);
        assertEquals(1,area.user); assertEquals(0x98,area.start); assertEquals(16,area.step); assertEquals(16,area.size); assertEquals(60,area.count);
    }

    @Test public void matchesCatalogIdentitiesToOurModels() {
        assertEquals("HEM-7155T",OmronConfig.ours("HEM-7155T_ESL",null,null).id);
        assertEquals("catalog name of the AP entry","HEM-7156T",OmronConfig.ours("HEM-7156T_AP","HEM-7156T-AP","HEM-7156T").id);
        assertEquals("config model_id","HEM-7361T",OmronConfig.ours("HEM-7361T_X","HEM-7361T-E",null).id);
        assertNull(OmronConfig.ours("HEM-0000T_X","HEM-0000T-X","BP0000"));
    }

    @Test public void everyAcceptedIdentity() throws Exception {
        OmronApk apk=OmronApk.assumePresent();
        OmronCatalog catalog=new OmronCatalog(new String(Files.readAllBytes(apk.deviceModelsJson),StandardCharsets.UTF_8));
        StringBuilder report=new StringBuilder("Omron "+apk.version+" memory maps vs OpenPressure (identities OpenPressure accepts)\n\n");
        int compared=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(apk.deviceCategoryZip))) {
            for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                String name=e.getName();
                if(name.startsWith("__MACOSX") || !name.matches(".*/0x0000/[^/]+/DeviceConfig\\.sys")) continue;
                String identifier=name.replaceAll(".*/0x0000/([^/]+)/DeviceConfig\\.sys","$1");
                OmronConfig config=OmronConfig.parse(new String(zip.readAllBytes(),StandardCharsets.UTF_8));
                VendorMemoryModel ours=OmronConfig.ours(identifier,config.modelId,catalog.name(identifier));
                if(ours==null) continue;
                report.append("accepted as ").append(OmronConfig.acceptedName(identifier,config.modelId,catalog.name(identifier))).append(": ")
                    .append(OmronConfig.compare(identifier,config,ours)).append('\n');
                compared++;
            }
        }
        assertTrue("accepted identities found: "+compared,compared>=10);
        Path out=Paths.get("build","omron-sandbox","memory-map.txt");
        Files.createDirectories(out.getParent());
        Files.write(out,report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
    }
}
