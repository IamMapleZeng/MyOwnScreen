package com.myownscreen;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TestActivity extends Activity {
    private Chronometer counter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setBackgroundColor(Color.rgb(19, 40, 60));
        int padding = Math.round(32 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        setContentView(content);

        appendText(content, getString(R.string.test_title), 28);
        appendText(content, getString(R.string.test_body), 16);

        counter = new Chronometer(this);
        counter.setTextSize(32);
        counter.setTextColor(Color.rgb(123, 223, 198));
        counter.setGravity(Gravity.CENTER);
        counter.setBase(SystemClock.elapsedRealtime());
        counter.setOnChronometerTickListener(view -> {
            long seconds = (SystemClock.elapsedRealtime() - view.getBase()) / 1000;
            view.setText(getString(R.string.test_counter, seconds));
        });
        content.addView(counter);
    }

    private void appendText(LinearLayout content, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, Math.round(16 * getResources().getDisplayMetrics().density));
        content.addView(view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        counter.start();
    }

    @Override
    protected void onStop() {
        counter.stop();
        super.onStop();
    }
}
