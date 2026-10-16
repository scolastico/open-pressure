package org.openpressure.app.pdf;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.openpressure.app.data.Reading;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Device-side PDF fixtures. Synthetic data only; never included in the application APK. */
public final class PdfReportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            File directory=new File(getTargetContext().getCacheDir(),"pdf-fixtures");
            if(!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Cannot create fixture directory");
            for(String language:new String[]{"en","de","ar"}) {
                Configuration config=new Configuration(getTargetContext().getResources().getConfiguration());config.setLocale(Locale.forLanguageTag(language));
                Context context=getTargetContext().createConfigurationContext(config);
                List<Reading> readings=new ArrayList<>();
                for(int i=0;i<121;i++) {
                    Reading reading=new Reading();reading.id=i+1;reading.user=i%2+1;
                    reading.time=LocalDateTime.of(2026,10,1,8,0).plusHours(i*3L).plusSeconds(i==0?37:0).toString();
                    reading.received=1792000000000L+i*1000;reading.systolic=110+i%31;reading.diastolic=65+i%20;reading.pulse=i%9==0?null:60.0+i%25;
                    reading.map=i%5==0?null:90.0;reading.unit="mmHg";reading.name="FixtureMonitor";reading.device="00:00:00:00:00:00";
                    reading.source=i%3==0?"manual":"vendor-history";reading.status=i%4==0?null:5;reading.afib=i%3==0?null:i%3==1;
                    if(i==1) {reading.unit="kPa";reading.systolic=16;reading.diastolic=10.7;reading.map=12.2;}
                    if(i==2) reading.time=null;
                    reading.notes="RecordMarker"+String.format(Locale.ROOT,"%03d",i+1);
                    if(i==60) reading.notes+="\nLongNoteStart "+repeat("Full note text. ",900)+" LongNoteEnd\nUnicode: Grüße · العربية · 測定";
                    readings.add(reading);
                }
                PdfReportData data=PdfReportData.create(readings,0,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),ZoneId.of("Europe/Berlin"));
                File output=new File(directory,"report-"+language+".pdf");
                try(FileOutputStream stream=new FileOutputStream(output)) { PdfReportWriter.write(context,data,stream); }
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)) {
                    if(pdf.getPageCount()<8) throw new AssertionError("Missing chart/table pagination");
                    for(int n=0;n<pdf.getPageCount();n++) try(PdfRenderer.Page page=pdf.openPage(n)) {
                        if(n==0 ? page.getWidth()>=page.getHeight() : page.getWidth()<=page.getHeight()) throw new AssertionError("Wrong orientation on page "+n);
                    }
                    result.putInt(language+"_pages",pdf.getPageCount());
                }
            }
            result.putString("result","PDF fixtures passed: orientation and multipage rendering");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) { result.putString("failure",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result); }
    }
    private static String repeat(String text,int count) { StringBuilder result=new StringBuilder();for(int i=0;i<count;i++) result.append(text);return result.toString(); }
}
