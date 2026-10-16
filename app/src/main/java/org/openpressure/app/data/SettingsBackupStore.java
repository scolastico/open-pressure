package org.openpressure.app.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** A new, flushed settings snapshot must exist before registration can be authorized. */
public final class SettingsBackupStore {
    public interface DirectorySync { void sync(File directory) throws IOException; }
    private SettingsBackupStore() {}
    public static File save(File directory,byte[] contents,DirectorySync sync) throws IOException {
        if(contents==null || contents.length==0) throw new IOException("Empty settings backup");
        if(sync==null) throw new IOException("Missing backup directory synchronization");
        Files.createDirectories(directory.toPath());
        String name="x7-settings-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json";
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
}
