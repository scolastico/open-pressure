package org.openpressure.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;
import org.openpressure.app.data.Reading;

/** A trend of recorded values, without diagnostic thresholds or interpolated readings. */
public final class TrendView extends View {
    private final List<Reading> readings;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final java.text.NumberFormat numbers;
    public TrendView(Context context,List<Reading> readings) { super(context); this.readings=readings; numbers=java.text.NumberFormat.getIntegerInstance(context.getResources().getConfiguration().getLocales().get(0)); setContentDescription(context.getString(R.string.monitor_trend_accessibility)); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); if(readings.isEmpty()) return;
        float density=getResources().getDisplayMetrics().density,left=34*density,right=getWidth()-8*density,top=20*density,bottom=getHeight()-28*density;
        double low=Double.MAX_VALUE,high=0; for(Reading r:readings) { low=Math.min(low,r.diaMmHg()); high=Math.max(high,r.sysMmHg()); }
        low=Math.floor((low-10)/10)*10; high=Math.ceil((high+10)/10)*10;
        paint.setTextSize(10*density); paint.setStrokeWidth(density);
        for(int i=0;i<3;i++) { float y=top+(bottom-top)*i/2; paint.setColor(0xffdce5de); c.drawLine(left,y,right,y,paint); paint.setColor(0xff61726b); c.drawText(numbers.format((int)(high-(high-low)*i/2)),0,y+4*density,paint); }
        for(int series=0;series<2;series++) {
            paint.setColor(series==0?0xff176e65:0xffbe754d); paint.setStrokeWidth(2*density); paint.setStyle(Paint.Style.STROKE); path.reset();
            for(int i=0;i<readings.size();i++) {
                Reading r=readings.get(i); float x=readings.size()==1?(left+right)/2:left+(right-left)*i/(readings.size()-1);
                float y=bottom-(float)(((series==0?r.sysMmHg():r.diaMmHg())-low)/(high-low))*(bottom-top);
                if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
            } c.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
        }
        String systolic=getContext().getString(R.string.monitor_systolic),diastolic=getContext().getString(R.string.monitor_diastolic);
        float gap=20*density,needed=paint.measureText(systolic)+paint.measureText(diastolic)+gap;
        if(needed>right-left) paint.setTextSize(paint.getTextSize()*(right-left)/needed);
        paint.setColor(0xff176e65); c.drawText(systolic,left,getHeight()-5*density,paint);
        paint.setColor(0xffbe754d); c.drawText(diastolic,left+paint.measureText(systolic)+gap,getHeight()-5*density,paint);
    }
}
