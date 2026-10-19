package org.openpressure.omronsandbox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Extraction of the user-supplied Omron package into the files the sandbox loads. */
public class OmronApkTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void extractsFromSyntheticXapk() throws Exception {
        byte[] categories=zip(new String[][]{{"device_category/0x0000/HEM-0000T/DeviceConfig.sys","[model]\nmodel_id=HEM-0000T\n"}});
        ByteArrayOutputStream base=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(base)) {
            entry(z,"res/raw/device_category.zip",categories);
            entry(z,"assets/Device/device_models.json","[]".getBytes(StandardCharsets.UTF_8));
        }
        Path input=temp.getRoot().toPath().resolve("synthetic.xapk");
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(input))) {
            entry(z,"manifest.json","{\"version_name\":\"9.9.9\",\"version_code\":\"99\"}".getBytes(StandardCharsets.UTF_8));
            entry(z,"com.example.apk",base.toByteArray());
        }
        Path out=temp.getRoot().toPath().resolve("out");
        ExtractOmron.main(new String[]{input.getParent().toString(),out.toString(),"--no-dex"});
        assertArrayEquals(categories,Files.readAllBytes(out.resolve("device_category.zip")));
        assertEquals("[]",read(out.resolve("device_models.json")));
        assertEquals("9.9.9 (99)",read(out.resolve("version.txt")).trim());
        assertFalse("no classes without dex conversion",Files.exists(out.resolve("omron-classes.jar")));
    }

    @Test public void staleExtractionIsIgnoredWhenThePackageWasRemoved() throws Exception {
        Path extracted=temp.newFolder("extracted").toPath(),apks=temp.newFolder("apk").toPath();
        Files.write(extracted.resolve("version.txt"),"9.9.9 (99)\n".getBytes(StandardCharsets.UTF_8));
        Files.write(extracted.resolve("omron-classes.jar"),new byte[0]);
        assertNull("no package left in the apk folder",OmronApk.locate(extracted,apks));
        Files.write(apks.resolve("omron.xapk"),new byte[0]);
        assertEquals("9.9.9 (99)",OmronApk.locate(extracted,apks).version);
    }

    @Test public void missingInputIsReportedNotThrownLater() throws Exception {
        Path empty=temp.newFolder("empty").toPath();
        try { ExtractOmron.main(new String[]{empty.toString(),temp.getRoot().toPath().resolve("o").toString(),"--no-dex"}); fail("empty input accepted"); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("docs/omron-sandbox.md")); }
    }

    @Test public void replacingPackageWithoutArm64SplitRemovesThePreviousNativeLibrary() throws Exception {
        byte[] categories=zip(new String[][]{{"device_category/synthetic","synthetic config"}});
        ByteArrayOutputStream base=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(base)) {
            entry(z,"res/raw/device_category.zip",categories);
            entry(z,"assets/Device/device_models.json","[]".getBytes(StandardCharsets.UTF_8));
        }
        byte[] nativeBytes="synthetic non-executable library".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream split=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(split)) { entry(z,"lib/arm64-v8a/libnativeOmsec.so",nativeBytes); }
        Path input=temp.newFolder("package").toPath(),out=temp.newFolder("extracted").toPath();
        Files.write(input.resolve("base.apk"),base.toByteArray());
        Path arm64=input.resolve("arm64.apk"); Files.write(arm64,split.toByteArray());
        String[] args={input.toString(),out.toString(),"--no-dex"};
        ExtractOmron.main(args);
        Path library=out.resolve("lib/arm64-v8a/libnativeOmsec.so");
        assertArrayEquals(nativeBytes,Files.readAllBytes(library));
        Files.delete(arm64);
        ExtractOmron.main(args);
        assertFalse("a base-only update must not silently use another version's native library",Files.exists(library));
    }

    private static String read(Path p) throws IOException { return new String(Files.readAllBytes(p),StandardCharsets.UTF_8); }
    private static byte[] zip(String[][] files) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(out)) { for(String[] f:files) entry(z,f[0],f[1].getBytes(StandardCharsets.UTF_8)); }
        return out.toByteArray();
    }
    private static void entry(ZipOutputStream z,String name,byte[] bytes) throws IOException {
        z.putNextEntry(new ZipEntry(name)); z.write(bytes); z.closeEntry();
    }
}
