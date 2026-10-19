package org.openpressure.app.data;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Saved-monitor import users, using real SQLite and synthetic device identities. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ReadingStoreDevicesTest {
    private Context context;
    private ReadingStore store;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); store=new ReadingStore(context); }
    @After public void close() { store.close(); }

    @Test public void newMonitorsImportBothUsersAndRowsCarryTheSelection() {
        assertEquals(8,store.getReadableDatabase().getVersion());
        assertEquals(3,store.importUsers("synthetic-unknown"));
        store.remember("synthetic-cuff","Synthetic","synthetic-model",1);
        assertEquals(3,store.importUsers("synthetic-cuff"));
        List<String[]> rows=store.devices();
        assertEquals(1,rows.size());
        assertArrayEquals(new String[]{"synthetic-cuff","Synthetic","synthetic-model","1","3",null},rows.get(0));
    }

    @Test public void rememberingAgainPreservesTheImportUsers() {
        store.remember("synthetic-cuff","Synthetic","synthetic-model",1);
        store.setImportUsers("synthetic-cuff",2);
        store.remember("synthetic-cuff","Renamed","other-model",2);
        assertEquals(2,store.importUsers("synthetic-cuff"));
        assertArrayEquals(new String[]{"synthetic-cuff","Renamed","other-model","2","2",null},store.devices().get(0));
        store.setImportUsers("synthetic-cuff",1); assertEquals(1,store.importUsers("synthetic-cuff"));
        store.forget("synthetic-cuff"); assertEquals(3,store.importUsers("synthetic-cuff"));
        store.remember("synthetic-cuff","Synthetic","synthetic-model",1);
        assertEquals("A forgotten monitor starts again with both users",3,store.importUsers("synthetic-cuff"));
    }

    @Test public void invalidImportUsersAreRejectedWithoutChangingStorage() {
        store.remember("synthetic-cuff","Synthetic","synthetic-model",1);
        for(int invalid:new int[]{0,4,-1,Integer.MIN_VALUE,Integer.MAX_VALUE}) {
            try { store.setImportUsers("synthetic-cuff",invalid); fail("Only 1, 2 or 3 are valid: "+invalid); } catch(IllegalArgumentException expected) { }
        }
        assertEquals(3,store.importUsers("synthetic-cuff"));
    }

    @Test public void upgradeFromVersionSixKeepsSavedMonitorsWithBothUsers() {
        store.remember("synthetic-cuff","Synthetic","synthetic-model",2);
        SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("ALTER TABLE devices DROP COLUMN users"); db.execSQL("ALTER TABLE devices DROP COLUMN reported_model"); db.setVersion(6);
        store.close(); store=new ReadingStore(context);
        assertEquals(8,store.getReadableDatabase().getVersion());
        assertArrayEquals(new String[]{"synthetic-cuff","Synthetic","synthetic-model","2","3",null},store.devices().get(0));
        store.setImportUsers("synthetic-cuff",1); assertEquals(1,store.importUsers("synthetic-cuff"));
    }

    @Test public void reportedModelIsDisplayOnlyAndKeptBySaving() {
        store.setReportedModel("synthetic-unknown","HEM-7155T-EBK");
        assertTrue("unsaved monitors stay unsaved",store.devices().isEmpty());
        store.remember("synthetic-cuff","Synthetic","synthetic-hint",1);
        store.setReportedModel("synthetic-cuff","HEM-7155T-EBK");
        assertArrayEquals(new String[]{"synthetic-cuff","Synthetic","synthetic-hint","1","3","HEM-7155T-EBK"},store.devices().get(0));
        assertEquals("HEM-7155T-EBK",ReadingStore.displayModel(store.devices().get(0)));
        store.remember("synthetic-cuff","Synthetic","synthetic-hint",1);
        assertEquals("a later save keeps the reported model","HEM-7155T-EBK",store.devices().get(0)[5]);
        store.setReportedModel("synthetic-cuff","");
        assertEquals("an empty reported model names the row by its saved model","synthetic-hint",ReadingStore.displayModel(store.devices().get(0)));
    }

    @Test public void upgradeFromVersionSevenKeepsTheSavedModel() {
        store.remember("synthetic-cuff","Synthetic","HEM-7380T1-EOSL",2); store.setImportUsers("synthetic-cuff",1);
        SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("ALTER TABLE devices DROP COLUMN reported_model"); db.setVersion(7);
        store.close(); store=new ReadingStore(context);
        assertEquals(8,store.getReadableDatabase().getVersion());
        assertArrayEquals(new String[]{"synthetic-cuff","Synthetic","HEM-7380T1-EOSL","2","1",null},store.devices().get(0));
        assertEquals("HEM-7380T1-EOSL",ReadingStore.displayModel(store.devices().get(0)));
    }
}
