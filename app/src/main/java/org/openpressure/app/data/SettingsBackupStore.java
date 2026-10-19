package org.openpressure.app.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** A new, flushed settings snapshot must exist before registration or a clock-block write can be authorized. */
public final class SettingsBackupStore {
    public interface DirectorySync { void sync(File directory) throws IOException; }
    /** X7 pre-registration snapshots. */
    public static final String REGISTRATION_PREFIX="x7-settings-";
    /** Settings images saved before a clock-block write. */
    public static final String CLOCK_SYNC_PREFIX="clock-sync-settings-";
    private SettingsBackupStore() {}
    public static File save(File directory,byte[] contents,DirectorySync sync) throws IOException {
        return save(directory,REGISTRATION_PREFIX,contents,sync);
    }
    /** Saves under {@code <prefix><millis>-<uuid>.json}; the prefix keeps each kind of backup apart. */
    public static File save(File directory,String prefix,byte[] contents,DirectorySync sync) throws IOException {
        if(!REGISTRATION_PREFIX.equals(prefix) && !CLOCK_SYNC_PREFIX.equals(prefix)) throw new IOException("Unknown settings backup kind");
        if(contents==null || contents.length==0) throw new IOException("Empty settings backup");
        if(sync==null) throw new IOException("Missing backup directory synchronization");
        Files.createDirectories(directory.toPath());
        String name=prefix+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json";
        File temporary=new File(directory,name+".tmp"), destination=new File(directory,name);
        try {
            try(FileOutputStream stream=new FileOutputStream(temporary)) {
                stream.write(contents); stream.flush(); stream.getFD().sync();
            }
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
            sync.sync(directory);
            if(directory.getParentFile()!=null) sync.sync(directory.getParentFile());
            return destination;
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    /** The newest completed registration snapshot in {@code directory}, or null; clock-sync backups are ignored. */
    public static File latestRegistrationBackup(File directory) { return latest(directory,REGISTRATION_PREFIX); }
    /** The newest completed clock-sync snapshot in {@code directory}, or null; registration backups are ignored. */
    public static File latestClockSyncBackup(File directory) { return latest(directory,CLOCK_SYNC_PREFIX); }
    private static File latest(File directory,String prefix) {
        File[] backups=directory.listFiles((parent,name) -> name.startsWith(prefix) && name.endsWith(".json"));
        File latest=null;
        if(backups!=null) for(File backup:backups) if(backup.isFile() && (latest==null || backup.lastModified()>latest.lastModified())) latest=backup;
        return latest;
    }
}
