package org.openpressure.app.pdf;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.robolectric.RuntimeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.data.Reading;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** A single-profile export names the profile once per page in the footer; an all-profiles export keeps it per row. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
public class PdfProfileLayoutTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void singleBankExportShowsTheProfileInEveryFooterAndNotPerRowOrChart() throws Exception {
        PdfReportData report=PdfReportData.create(Arrays.asList(reading(1,1,null),reading(2,2,null),reading(3,1,null)),1,null,null,ZoneId.of("UTC"));
        List<List<String>> pages=render(report);
        String user=context.getString(R.string.main_user_number,"1");
        assertSingleProfile(pages,user);
    }

    @Test public void selectedVirtualProfileIsAlsoShownOnlyInTheFooter() throws Exception {
        PdfReportData report=PdfReportData.createVirtual(Arrays.asList(reading(1,1,7),reading(2,2,7),reading(3,1,8)),-7,null,null,ZoneId.of("UTC"),Map.of(-7,"Synthetic Alice",-8,"Synthetic Bob"));
        List<List<String>> pages=render(report);
        assertSingleProfile(pages,"Synthetic Alice");
        for(List<String> page:pages) assertFalse(page.toString(),page.toString().contains("Synthetic Bob"));
    }

    @Test public void allProfilesExportKeepsTheProfileColumnAndChartTitles() throws Exception {
        PdfReportData report=PdfReportData.create(Arrays.asList(reading(1,1,null),reading(2,2,null)),0,null,null,ZoneId.of("UTC"));
        List<List<String>> pages=render(report);
        String one=context.getString(R.string.main_user_number,"1"),two=context.getString(R.string.main_user_number,"2");
        String charts=context.getString(R.string.pdf_report_charts);
        assertEquals(4,pages.size());
        assertTrue(pages.get(1).contains(charts+" · "+one));
        assertTrue(pages.get(2).contains(charts+" · "+two));
        List<String> table=pages.get(3);
        assertTrue(table.contains(context.getString(R.string.pdf_report_profile)));
        assertTrue(table.contains(one));
        assertTrue(table.contains(two));
        for(List<String> page:pages) assertTrue(page.toString(),page.contains(footer(context.getString(R.string.main_all_users))));
    }

    private void assertSingleProfile(List<List<String>> pages,String user) {
        String charts=context.getString(R.string.pdf_report_charts);
        assertEquals("overview, one chart and one table page",3,pages.size());
        assertTrue(pages.get(0).toString(),pages.get(0).contains(context.getString(R.string.pdf_report_overview)+" · "+user));
        assertTrue(pages.get(1).toString(),pages.get(1).contains(charts));
        for(String text:pages.get(1)) assertFalse(text,text.startsWith(charts+" · "));
        List<String> table=pages.get(2);
        assertFalse(table.toString(),table.contains(context.getString(R.string.pdf_report_profile)));
        assertFalse(table.toString(),table.contains(user));
        assertTrue(table.contains(context.getString(R.string.pdf_report_notes)));
        for(List<String> page:pages) assertTrue(page.toString(),page.contains(footer(user)));
    }
    private String footer(String user) { return "OpenPressure · "+org.openpressure.app.BuildConfig.VERSION_NAME+" · "+user; }
    private List<List<String>> render(PdfReportData report) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        BitmapPages target=new BitmapPages();
        List<List<String>> pages=PdfReportWriter.writeAndCollectText(context,report,target,output);
        assertEquals(pages.size(),target.finished);
        assertTrue(target.closed);
        return pages;
    }
    /** Robolectric's PdfDocument has no native document, so pages are drawn on bitmaps of the same size. */
    private static final class BitmapPages implements PdfReportWriter.Pages {
        int finished; boolean open,closed;
        @Override public Canvas start(int width,int height,int number) {
            assertFalse(open); open=true; assertEquals(finished+1,number);
            return new Canvas(Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888));
        }
        @Override public void finish() { if(open) finished++; open=false; }
        @Override public void writeTo(OutputStream output) { assertFalse(open); }
        @Override public void close() { finish(); closed=true; }
    }
    private static Reading reading(long id,int bank,Integer profile) {
        Reading reading=new Reading();
        reading.id=id; reading.user=bank; reading.virtualUserId=profile; reading.time="2026-01-0"+id+"T08:00:00";
        reading.systolic=120+id; reading.diastolic=80; reading.pulse=60.0; reading.unit="mmHg";
        reading.device="00:11:22:33:44:0"+id; reading.name="BLEsmart_0000000"+id; reading.source="monitor"; reading.notes="Synthetic note "+id;
        return reading;
    }
}
