package org.openpressure.app.data;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import static org.junit.Assert.*;

public class SettingsBackupStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void backupsPersistBeforeReturningAndNeverReplaceEarlierCapture() throws Exception {
        File directory=new File(temporary.getRoot(),"backups");
        byte[] original="{\"data\":\"aabb\"}".getBytes(StandardCharsets.UTF_8);
        File first=SettingsBackupStore.save(directory,original,SettingsBackupStoreTest::syncDirectory);
        File second=SettingsBackupStore.save(directory,"{\"data\":\"ccdd\"}".getBytes(StandardCharsets.UTF_8),SettingsBackupStoreTest::syncDirectory);
        assertArrayEquals(original,Files.readAllBytes(first.toPath()));
        assertNotEquals(first,second);
        assertEquals("{\"data\":\"ccdd\"}",new String(Files.readAllBytes(second.toPath()),StandardCharsets.UTF_8));
        assertEquals(2,directory.listFiles().length);
    }
    @Test public void clockSyncBackupsAreNamedApartAndNeverChosenForRegistration() throws Exception {
        File directory=new File(temporary.getRoot(),"backups");
        File registration=SettingsBackupStore.save(directory,new byte[]{1},SettingsBackupStoreTest::syncDirectory);
        assertTrue(registration.getName().matches("x7-settings-\\d+-[0-9a-f-]{36}\\.json"));
        File clock=SettingsBackupStore.save(directory,SettingsBackupStore.CLOCK_SYNC_PREFIX,new byte[]{2},SettingsBackupStoreTest::syncDirectory);
        assertTrue(clock.getName().matches("clock-sync-settings-\\d+-[0-9a-f-]{36}\\.json"));
        assertTrue(clock.setLastModified(registration.lastModified()+60000));
        assertEquals("the newer clock-sync file is ignored",registration,SettingsBackupStore.latestRegistrationBackup(directory));
        assertTrue(registration.delete());
        assertNull(SettingsBackupStore.latestRegistrationBackup(directory));
        try { SettingsBackupStore.save(directory,"other-",new byte[]{3},SettingsBackupStoreTest::syncDirectory); fail("Unknown backup kinds are refused"); }
        catch(IOException expected) { assertEquals(1,directory.listFiles().length); }
    }
    @Test public void failedStorageCannotReturnAUsableBackup() throws Exception {
        File directory=temporary.newFile("not-a-directory");
        try { SettingsBackupStore.save(directory,new byte[]{1},SettingsBackupStoreTest::syncDirectory); fail("Storage failure must stop registration"); }
        catch(IOException expected) { assertTrue(directory.isFile()); }
        assertEquals(0,Files.size(directory.toPath()));
    }
    @Test public void directorySyncFailureCannotBeReportedAsASavedBackup() throws Exception {
        try { SettingsBackupStore.save(temporary.getRoot(),new byte[]{1},directory -> { throw new IOException("Directory sync failed"); }); fail("Backup must be durable before registration"); }
        catch(IOException expected) { assertEquals("Directory sync failed",expected.getMessage()); }
    }
    private static void syncDirectory(File directory) throws IOException {
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)) { channel.force(true); }
    }
    @Test public void emptyCaptureCannotBecomeARegistrationBackup() throws Exception {
        try { SettingsBackupStore.save(temporary.getRoot(),new byte[0],SettingsBackupStoreTest::syncDirectory); fail("No empty backup"); }
        catch(IOException expected) { assertEquals(0,temporary.getRoot().listFiles().length); }
    }
}
