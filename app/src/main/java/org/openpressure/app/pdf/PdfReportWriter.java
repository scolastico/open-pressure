package org.openpressure.app.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import org.openpressure.app.BuildConfig;
import org.openpressure.app.R;
import org.openpressure.app.ui.MonitorUi;
import java.io.IOException;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native, offline PDF rendering. All table content wraps and oversized rows continue by whole lines. */
public final class PdfReportWriter {
    private static final int INK=0xff182d2a, MUTED=0xff61726b, TEAL=0xff176e65, ORANGE=0xffbe754d, GRID=0xffdce5de;
    private static final int MARGIN=36;
    private final Context context;
    private final PdfReportData report;
    private final PdfDocument document=new PdfDocument();
    private final Locale locale;
    private final NumberFormat numbers;
    private final DateTimeFormatter date,dateTime,shortTime;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber,width,height;
    private float tableTop,tableBottom;
    private final int[] columns={36,96,42,92,42,48,112,106,196};

    private PdfReportWriter(Context context,PdfReportData report) {
        this.context=context;this.report=report;
        locale=context.getResources().getConfiguration().getLocales().get(0);
        numbers=NumberFormat.getNumberInstance(locale);numbers.setMaximumFractionDigits(3);numbers.setGroupingUsed(false);
        date=DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        dateTime=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.MEDIUM).withLocale(locale);
        shortTime=DateTimeFormatter.ofPattern("dd MMM\nHH:mm",locale);
    }
    public static void write(Context context,PdfReportData report,OutputStream output) throws IOException {
        if(context==null || report==null || output==null || report.entries.isEmpty()) throw new IllegalArgumentException("A report needs selected readings");
        PdfReportWriter writer=new PdfReportWriter(context,report);
        try {
            writer.overview();
            for(PdfReportData.ChartPage chart:report.charts) writer.chart(chart);
            writer.tables();writer.finishPage();
            if(Thread.currentThread().isInterrupted()) throw new IOException("PDF export cancelled");
            writer.document.writeTo(output);
        } finally {
            if(writer.page!=null) writer.document.finishPage(writer.page);
            writer.document.close();
        }
    }
    private String s(int id,Object... args) { return context.getString(id,args); }
    private String n(double value) { return Double.isFinite(value)?numbers.format(value):s(R.string.main_unavailable); }
    private String average(double value) {
        if(!Double.isFinite(value)) return s(R.string.main_unavailable);
        NumberFormat format=(NumberFormat)numbers.clone();format.setMaximumFractionDigits(1);return format.format(value);
    }
    private String profile(Integer user) {
        if(report.virtualUsers) return user==null?s(R.string.vp_unassigned):report.profileNames.getOrDefault(user,s(R.string.vp_unassigned));
        return user==null?s(R.string.main_user_unspecified):user==255?s(R.string.main_guest):s(R.string.main_user_number,n(user));
    }
    private String selected() { return report.selectedUser==0?s(R.string.main_all_users):profile(report.selectedUser); }
    private String period() {
        LocalDate from=report.from==null?report.entries.get(0).displayTime.toLocalDate():report.from;
        LocalDate to=report.to==null?report.entries.get(report.entries.size()-1).displayTime.toLocalDate():report.to;
        return s(R.string.pdf_report_period,from.format(date),to.format(date));
    }
    private void newPage(boolean portrait,String title,String subtitle) throws IOException {
        if(Thread.currentThread().isInterrupted()) throw new IOException("PDF export cancelled");
        finishPage();width=portrait?595:842;height=portrait?842:595;
        page=document.startPage(new PdfDocument.PageInfo.Builder(width,height,++pageNumber).create());canvas=page.getCanvas();
        text("OpenPressure",MARGIN,22,width-2*MARGIN,12,true,TEAL);
        float y=47+text(title,MARGIN,47,width-2*MARGIN,21,true,INK);
        text(subtitle,MARGIN,y+7,width-2*MARGIN,10,false,MUTED);
    }
    private void finishPage() {
        if(page==null) return;
        line(MARGIN,height-37,width-MARGIN,height-37,GRID,0.6f);
        text("OpenPressure · "+BuildConfig.VERSION_NAME,MARGIN,height-28,width-2*MARGIN-100,8,false,MUTED);
        StaticLayout number=layout(s(R.string.pdf_report_page,n(pageNumber)),94,8,false,MUTED,Layout.Alignment.ALIGN_OPPOSITE);
        draw(number,width-MARGIN-94,height-28);
        document.finishPage(page);page=null;
    }
    private void overview() throws IOException {
        newPage(true,s(R.string.pdf_report_title),s(R.string.pdf_report_overview)+" · "+selected());
        float y=110;
        y+=text(period(),MARGIN,y,width-2*MARGIN,13,true,INK)+8;
        y+=text(s(R.string.pdf_report_generated,LocalDateTime.now(report.zone).format(dateTime)),MARGIN,y,width-2*MARGIN,9,false,MUTED)+18;
        y+=text(s(R.string.pdf_report_count,n(report.entries.size())),MARGIN,y,width-2*MARGIN,16,true,TEAL)+18;
        String notes=s(R.string.pdf_report_time_note)+"\n\n"+s(R.string.pdf_report_flags_note)+"\n\n"+s(R.string.pdf_report_chart_note);
        float noteY=height-50-layout(notes,width-2*MARGIN,9,false,MUTED,Layout.Alignment.ALIGN_NORMAL).getHeight();
        for(PdfReportData.Summary summary:report.summaries) {
            String heading=profile(summary.user)+" · "+s(R.string.pdf_report_count,n(summary.count));
            String metrics=s(R.string.pdf_report_average_pressure,"\u2066"+average(summary.systolicAverage),average(summary.diastolicAverage)+"\u2069")+"\n"
                    +s(R.string.pdf_report_pressure_range,"\u2066"+n(summary.systolicMin),n(summary.systolicMax)+"\u2069","\u2066"+n(summary.diastolicMin),n(summary.diastolicMax)+"\u2069")+"\n"
                    +s(R.string.pdf_report_average_pulse,average(summary.pulseAverage))+"\n"
                    +s(R.string.pdf_report_pulse_range,"\u2066"+n(summary.pulseMin),n(summary.pulseMax)+"\u2069");
            StaticLayout headingLayout=layout(heading,width-2*MARGIN,12,true,INK,Layout.Alignment.ALIGN_NORMAL);
            StaticLayout metricsLayout=layout(metrics,width-2*MARGIN,10,false,MUTED,Layout.Alignment.ALIGN_NORMAL);
            if(y+headingLayout.getHeight()+metricsLayout.getHeight()+24>noteY-35) {
                text(s(R.string.pdf_report_more_profiles),MARGIN,y,width-2*MARGIN,10,false,MUTED);break;
            }
            line(MARGIN,y,width-MARGIN,y,GRID,0.6f);y+=10;
            draw(headingLayout,MARGIN,y);y+=headingLayout.getHeight()+5;
            draw(metricsLayout,MARGIN,y);y+=metricsLayout.getHeight()+15;
        }
        text(notes,MARGIN,noteY,width-2*MARGIN,9,false,MUTED);
    }
    private void chart(PdfReportData.ChartPage chart) throws IOException {
        newPage(false,s(R.string.pdf_report_charts)+" · "+profile(chart.user),period()+" · "+s(R.string.pdf_report_count,n(chart.entries.size())));
        text(s(R.string.pdf_report_pressure)+" (mmHg)",MARGIN,105,350,11,true,INK);
        text(s(R.string.monitor_systolic),400,105,175,10,true,TEAL);
        text(s(R.string.monitor_diastolic),590,105,190,10,true,ORANGE);
        plot(chart.entries,130,310,false);
        text(s(R.string.pdf_report_pulse)+" (bpm)",MARGIN,360,350,11,true,INK);
        plot(chart.entries,382,490,true);
    }
    private void plot(List<PdfReportData.Entry> entries,float top,float bottom,boolean pulse) {
        float left=73,right=width-MARGIN-12;
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(PdfReportData.Entry entry:entries) {
            double a=pulse?(entry.pulse==null?Double.NaN:entry.pulse):entry.sysMmHg(),b=pulse?a:entry.diaMmHg();
            if(Double.isFinite(a)) {low=Math.min(low,a);high=Math.max(high,a);}
            if(Double.isFinite(b)) {low=Math.min(low,b);high=Math.max(high,b);}
        }
        if(!Double.isFinite(low)) {
            text(s(pulse?R.string.pdf_report_no_pulse:R.string.pdf_report_no_pressure),left,(top+bottom)/2,(int)(right-left),11,false,MUTED);return;
        }
        low=Math.floor((low-5)/10)*10;high=Math.ceil((high+5)/10)*10;
        double target=(high-low)/4,magnitude=Math.pow(10,Math.floor(Math.log10(target))),fraction=target/magnitude;
        double step=(fraction<1.5?1:fraction<3?2:fraction<7?5:10)*magnitude;
        low=Math.floor(low/step)*step;high=Math.ceil(high/step)*step;
        int ticks=(int)Math.round((high-low)/step);
        for(int i=0;i<=ticks;i++) {
            float y=top+(bottom-top)*i/ticks;
            line(left,y,right,y,GRID,0.5f);
            text(n(high-step*i),MARGIN,y-6,34,8,false,MUTED);
        }
        double first=seconds(entries.get(0).displayTime),last=seconds(entries.get(entries.size()-1).displayTime);
        for(int series=0;series<(pulse?1:2);series++) {
            boolean previous=false;float px=0,py=0;int color=series==0?TEAL:ORANGE;
            for(int i=0;i<entries.size();i++) {
                PdfReportData.Entry entry=entries.get(i);
                double value=pulse?(entry.pulse==null?Double.NaN:entry.pulse):series==0?entry.sysMmHg():entry.diaMmHg();
                if(!Double.isFinite(value)) {previous=false;continue;}
                float x=last==first?(left+right)/2:left+(float)((seconds(entry.displayTime)-first)/(last-first))*(right-left);
                float y=bottom-(float)((value-low)/(high-low))*(bottom-top);
                if(previous) line(px,py,x,y,color,1.1f);
                paint.setColor(color);paint.setStyle(entry.time==null?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(1);
                canvas.drawCircle(x,y,2.5f,paint);paint.setStyle(Paint.Style.FILL);
                px=x;py=y;previous=true;
            }
        }
        for(int tick=0;tick<5;tick++) {
            int index=(entries.size()-1)*tick/4;
            if(tick>0 && index==(entries.size()-1)*(tick-1)/4) continue;
            PdfReportData.Entry entry=entries.get(index);
            float x=last==first?(left+right)/2:left+(float)((seconds(entry.displayTime)-first)/(last-first))*(right-left);
            text(entry.displayTime.format(shortTime),Math.min(right-58,Math.max(left-8,x-24)),bottom+8,72,8,false,MUTED);
        }
    }
    private static double seconds(LocalDateTime time) { return time.toLocalDate().toEpochDay()*86400d+time.toLocalTime().toSecondOfDay(); }
    private void tablePage() throws IOException {
        newPage(false,s(R.string.pdf_report_records),selected()+" · "+period());
        String[] headers={s(R.string.pdf_report_number),s(R.string.pdf_report_date),s(R.string.pdf_report_profile),s(R.string.pdf_report_pressure),s(R.string.pdf_report_pulse),s(R.string.pdf_report_map),s(R.string.pdf_report_flags),s(R.string.pdf_report_device),s(R.string.pdf_report_notes)};
        float x=MARGIN;int headerHeight=0;StaticLayout[] layouts=new StaticLayout[headers.length];
        for(int i=0;i<headers.length;i++) {layouts[i]=layout(headers[i],columns[i]-10,8,true,TEAL,Layout.Alignment.ALIGN_NORMAL);headerHeight=Math.max(headerHeight,layouts[i].getHeight());}
        paint.setColor(0xffe9efea);canvas.drawRect(MARGIN,105,width-MARGIN,115+headerHeight,paint);
        for(int i=0;i<headers.length;i++) {draw(layouts[i],x+5,110);x+=columns[i];}
        tableTop=119+headerHeight;tableBottom=height-47;
    }
    private void tables() throws IOException {
        tablePage();float y=tableTop;int row=0;
        for(PdfReportData.Entry entry:report.entries) {
            if(Thread.currentThread().isInterrupted()) throw new IOException("PDF export cancelled");
            row++;
            String stamp=entry.displayTime.format(dateTime);
            if(entry.time==null) stamp=s(R.string.main_received_time,stamp);
            List<String> flags=entry.status==null?new ArrayList<>():MonitorUi.readingFlags(context,entry.status);
            if(entry.status==null) flags.add(s(R.string.main_unavailable));
            else if(flags.isEmpty()) flags.add(s(R.string.pdf_report_no_flags));
            flags.add(s(R.string.main_afib_detail,entry.afib==null?s(R.string.main_unavailable):s(entry.afib?R.string.main_afib_flagged:R.string.main_afib_not_flagged)));
            String pressure="\u2066\u2066"+n(entry.systolic)+"\u2069 / \u2066"+n(entry.diastolic)+"\u2069\u2069\n"+entry.unit;
            String[] cells={n(row),stamp,profile(entry.user),pressure,entry.pulse==null?"—":n(entry.pulse)+" bpm",entry.map==null?"—":n(entry.map)+" "+entry.unit,String.join("\n",flags),entry.name+"\n"+entry.device+"\n"+s("manual".equals(entry.source)?R.string.main_source_manual:R.string.main_source_monitor),entry.notes};
            StaticLayout[] layouts=new StaticLayout[cells.length];int[] consumed=new int[cells.length];
            for(int i=0;i<cells.length;i++) layouts[i]=layout(cells[i],columns[i]-10,8.5f,false,INK,Layout.Alignment.ALIGN_NORMAL);
            boolean more=true;
            while(more) {
                if(tableBottom-y<32) {tablePage();y=tableTop;}
                int remainingHeight=0;
                for(int i=0;i<layouts.length;i++) if(consumed[i]<layouts[i].getLineCount()) remainingHeight=Math.max(remainingHeight,layouts[i].getHeight()-layouts[i].getLineTop(consumed[i]));
                if(remainingHeight+10<=tableBottom-tableTop && remainingHeight+10>tableBottom-y) {tablePage();y=tableTop;}
                int available=(int)(tableBottom-y-10);int[] ends=new int[cells.length];int segmentHeight=0;
                for(int i=0;i<layouts.length;i++) {
                    int start=consumed[i],end=start;StaticLayout layout=layouts[i];
                    if(start<layout.getLineCount()) {
                        int origin=layout.getLineTop(start);
                        while(end<layout.getLineCount() && layout.getLineBottom(end)-origin<=available) end++;
                        if(end==start) throw new IOException("A table line cannot fit on a PDF page");
                        segmentHeight=Math.max(segmentHeight,layout.getLineBottom(end-1)-origin);
                    }
                    ends[i]=end;
                }
                float rowHeight=Math.max(24,Math.max(segmentHeight,layouts[0].getHeight())+10),x=MARGIN;
                if((row&1)==0) {paint.setColor(0xfff5f7f4);canvas.drawRect(MARGIN,y,width-MARGIN,y+rowHeight,paint);}
                for(int i=0;i<layouts.length;i++) {
                    StaticLayout layout=layouts[i];int start=consumed[i],end=ends[i];
                    if(start<end) {
                        canvas.save();canvas.clipRect(x+5,y+5,x+columns[i]-5,y+5+layout.getLineBottom(end-1)-layout.getLineTop(start));
                        canvas.translate(x+5,y+5-layout.getLineTop(start));layout.draw(canvas);canvas.restore();
                    } else if(i==0) text(cells[0],x+5,y+5,columns[i]-10,8.5f,false,INK);
                    consumed[i]=end;x+=columns[i];
                }
                line(MARGIN,y+rowHeight,width-MARGIN,y+rowHeight,GRID,0.5f);y+=rowHeight;
                more=false;for(int i=0;i<layouts.length;i++) if(consumed[i]<layouts[i].getLineCount()) more=true;
                if(more) {tablePage();y=tableTop;}
            }
        }
    }
    private StaticLayout layout(String value,int width,float size,boolean bold,int color,Layout.Alignment align) {
        TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setTextSize(size);text.setColor(color);
        text.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));text.setTextLocale(locale);
        return StaticLayout.Builder.obtain(value,0,value.length(),text,Math.max(1,width)).setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(true).setLineSpacing(1,1).build();
    }
    private float text(String value,float x,float y,int width,float size,boolean bold,int color) {
        StaticLayout layout=layout(value,width,size,bold,color,Layout.Alignment.ALIGN_NORMAL);draw(layout,x,y);return layout.getHeight();
    }
    private void draw(StaticLayout layout,float x,float y) {canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore();}
    private void line(float x1,float y1,float x2,float y2,int color,float stroke) {paint.setColor(color);paint.setStrokeWidth(stroke);canvas.drawLine(x1,y1,x2,y2,paint);}
}
