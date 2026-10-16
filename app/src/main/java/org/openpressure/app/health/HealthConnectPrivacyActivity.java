package org.openpressure.app.health;

import android.app.Activity;
import org.openpressure.app.R;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Read-only privacy screen, including when launched from Health Connect before onboarding. */
public final class HealthConnectPrivacyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int padding=dp(22); page.setPaddingRelative(padding,padding,padding,padding); page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(scroll);
        TextView heading=new TextView(this); heading.setText(getString(R.string.health_privacy_title)); heading.setTextSize(28); heading.setTextColor(0xff182d2a); heading.setTypeface(null,Typeface.BOLD); page.addView(heading);
        paragraph(page,getString(R.string.health_privacy_local));
        paragraph(page,getString(R.string.health_privacy_choice));
        paragraph(page,getString(R.string.health_privacy_shared));
        paragraph(page,getString(R.string.health_privacy_other_apps));
        paragraph(page,getString(R.string.health_privacy_delete));
        Button close=new Button(this); close.setText(getString(R.string.settings_close)); close.setAllCaps(false); close.setMinHeight(dp(50)); close.setOnClickListener(v -> finish()); page.addView(close);
    }
    private void paragraph(LinearLayout page,String value) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(16); text.setTextColor(0xff61726b); text.setLineSpacing(dp(3),1);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(18); page.addView(text,params);
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
