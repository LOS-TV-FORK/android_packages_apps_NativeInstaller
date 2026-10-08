package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Detection-first step: scans the chosen disk for other systems BEFORE
 * any mode is offered, then hands OS + map lines to ModeActivity.
 */
public class ScanActivity extends Activity {
    public static final String EXTRA_OS = "os_lines";
    public static final String EXTRA_SCAN = "scan_lines";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_list);
        final String disk = getIntent().getStringExtra(DiskActivity.EXTRA_DISK);

        ((TextView) findViewById(R.id.title))
                .setText(getString(R.string.scan_title, disk));
        TextView sub = findViewById(R.id.subtitle);
        sub.setVisibility(android.view.View.VISIBLE);
        sub.setText(R.string.part_scanning);
        Button go = findViewById(R.id.primary);
        go.setVisibility(android.view.View.GONE);

        LinearLayout list = findViewById(R.id.list);
        new Thread(() -> {
            final List<String> lines = Engine.scan(this);
            final List<String> oses = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("OS ")) {
                    oses.add(line.substring(3));
                }
            }
            runOnUiThread(() -> {
                list.removeAllViews();
                if (oses.isEmpty()) {
                    TextView t = new TextView(this);
                    t.setText(R.string.scan_none);
                    t.setTextSize(20);
                    list.addView(t, Ui.rowParams(this));
                } else {
                    for (String o : oses) {
                        String[] f = o.split("\\|", -1);
                        String row = f.length >= 2 ? f[1] : o;
                        if (f.length >= 4 && !f[3].isEmpty()) {
                            row += "  (" + f[3] + ")";
                        }
                        TextView t = new TextView(this);
                        t.setText(row);
                        t.setTextSize(20);
                        t.setFocusable(true);
                        list.addView(t, Ui.rowParams(this));
                    }
                }
                sub.setText(oses.isEmpty()
                        ? getString(R.string.scan_none_sub)
                        : getString(R.string.scan_found_sub, oses.size()));
                Button goBtn = findViewById(R.id.primary);
                goBtn.setVisibility(android.view.View.VISIBLE);
                goBtn.setText(R.string.next_btn);
                Ui.focusFx(goBtn);
                goBtn.setOnClickListener(v -> {
                    Intent i = new Intent(this, ModeActivity.class);
                    i.putExtra(DiskActivity.EXTRA_DISK, disk);
                    i.putStringArrayListExtra(EXTRA_OS,
                            new ArrayList<>(oses));
                    i.putStringArrayListExtra(EXTRA_SCAN,
                            new ArrayList<>(lines));
                    startActivity(i);
                });
                goBtn.requestFocus();
            });
        }).start();
    }
}
