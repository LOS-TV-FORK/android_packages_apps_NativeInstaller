package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Disk picker: dropdown list + refresh, SetupWizard TV look. */
public class DiskActivity extends Activity {
    public static final String EXTRA_DISK = "disk";

    private final List<Disks.Disk> mDisks = new ArrayList<>();
    private ArrayAdapter<String> mAdapter;
    private Spinner mSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_form);

        ((TextView) findViewById(R.id.title)).setText(R.string.disk_title);

        LinearLayout form = findViewById(R.id.form);

        mSpinner = new Spinner(this);
        mSpinner.setFocusable(true);
        form.addView(mSpinner, Ui.rowParams(this));

        Button refresh = Ui.styledPrimary(this, R.string.refresh_btn);
        refresh.setOnClickListener(v -> reload());
        form.addView(refresh, Ui.rowParams(this));

        Button go = findViewById(R.id.primary);
        go.setText(R.string.next_btn);
        Ui.focusFx(mSpinner);
        Ui.focusFx(go);
        go.setOnClickListener(
                v -> {
                    int pos = mSpinner.getSelectedItemPosition();
                    if (pos < 0 || pos >= mDisks.size()) {
                        return;
                    }
                    Intent i = new Intent(this, ScanActivity.class);
                    i.putExtra(EXTRA_DISK, mDisks.get(pos).dev);
                    startActivity(i);
                });

        reload();
        mSpinner.requestFocus();
    }

    private void reload() {
        mDisks.clear();
        mDisks.addAll(Disks.list());
        List<String> labels = new ArrayList<>();
        if (mDisks.isEmpty()) {
            labels.add(getString(R.string.disk_empty));
        } else {
            for (Disks.Disk d : mDisks) {
                labels.add(d.label(this));
            }
        }
        mAdapter =
                new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, labels);
        mAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(mAdapter);
    }
}
