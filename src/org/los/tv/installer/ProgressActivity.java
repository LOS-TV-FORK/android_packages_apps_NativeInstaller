package org.los.tv.installer;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Install progress (fixed bar) + auto-scrolling log + reboot. */
public class ProgressActivity extends Activity {
    private TextView mLog;
    private ProgressBar mBar;
    private ScrollView mScroll;
    private Button mReboot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.progress);

        ((TextView) findViewById(R.id.title))
                .setText(R.string.progress_title);
        mBar = findViewById(R.id.bar);
        mBar.setMax(100);
        mScroll = findViewById(R.id.scroll);
        mLog = findViewById(R.id.log);

        mReboot = findViewById(R.id.reboot);
        Ui.focusFx(mReboot);
        mReboot.setText(R.string.reboot_btn);
        mReboot.setOnClickListener(v -> reboot());

        Engine.Options opts = new Engine.Options();
        opts.disk = getIntent().getStringExtra(DiskActivity.EXTRA_DISK);
        opts.mode = getIntent().getStringExtra(ModeActivity.EXTRA_MODE);
        opts.dataImg =
                getIntent().getBooleanExtra(ModeActivity.EXTRA_DATA_IMG, false);
        opts.dataSizeMb =
                getIntent().getIntExtra(ModeActivity.EXTRA_DATA_SIZE, 0);
        opts.extra =
                getIntent().getStringExtra(ModeActivity.EXTRA_OPTS);

        new Thread(
                        () ->
                                Engine.run(
                                        this,
                                        opts,
                                        this::onLine,
                                        this::getString))
                .start();
    }

    private void onLine(final String s) {
        runOnUiThread(
                () -> {
                    if (s.startsWith("PCT ")) {
                        try {
                            mBar.setProgress(
                                    Integer.parseInt(s.substring(4).trim()));
                        } catch (NumberFormatException ignored) {
                        }
                        return;
                    }
                    mLog.append(s + "\n");
                    // Auto-scroll to the bottom, bar stays put (outside).
                    mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
                    if (s.startsWith(getString(R.string.inst_ok_done))) {
                        mReboot.setVisibility(View.VISIBLE);
                        mReboot.requestFocus();
                    }
                });
    }

    private void reboot() {
        PowerManager pm =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            pm.reboot(null);
        }
    }
}
