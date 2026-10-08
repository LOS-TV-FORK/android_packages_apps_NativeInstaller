package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Install mode + data.img + link to the full options tree. */
public class ModeActivity extends Activity {
    public static final String EXTRA_MODE = "mode"; // "disk", "part" or "alongside"
    public static final String EXTRA_ASIZE = "asize"; // MB system, alongside only, 0 = max
    public static final String EXTRA_DATA_IMG = "data_img";
    public static final String EXTRA_DATA_SIZE = "data_size"; // MB, 0 = max
    public static final String EXTRA_OPTS = "opts";

    private int mModeIdx = 0; // 0 wipe, 1 part, 2 alongside
    private Button mOptsBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_form);
        final String disk = getIntent().getStringExtra(DiskActivity.EXTRA_DISK);

        ((TextView) findViewById(R.id.title))
                .setText(getString(R.string.mode_title, disk));

        // Detection came first (ScanActivity): preselect alongside when
        // foreign systems were found.
        java.util.ArrayList<String> oses =
                getIntent().getStringArrayListExtra(ScanActivity.EXTRA_OS);
        if (oses != null && !oses.isEmpty()) {
            mModeIdx = 2;
        }

        LinearLayout form = findViewById(R.id.form);

        final Button modeBtn = Ui.styledPrimary(this,
                mModeIdx == 2 ? R.string.mode_alongside : R.string.mode_wipe);
        final int[] modeLabels =
                {R.string.mode_wipe, R.string.mode_part, R.string.mode_alongside};
        final String[] modeValues = {"disk", "part", "alongside"};
        modeBtn.setOnClickListener(
                v -> {
                    mModeIdx = (mModeIdx + 1) % 3;
                    modeBtn.setText(getString(modeLabels[mModeIdx]));
                });
        form.addView(modeBtn, Ui.rowParams(this));
        if (oses != null && !oses.isEmpty()) {
            TextView found = new TextView(this);
            StringBuilder sb = new StringBuilder();
            for (String o : oses) {
                String[] f = o.split("\\|", -1);
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(f.length >= 2 ? f[1] : o);
            }
            found.setText(getString(R.string.scan_found_sub, oses.size())
                    + "\n" + sb.toString());
            found.setTextSize(18);
            form.addView(found, Ui.rowParams(this));
        }

        CheckBox dataImg = option(form, R.string.opt_dataimg, false);
        EditText dataSize = new EditText(this);
        dataSize.setHint(R.string.opt_datasize_hint);
        dataSize.setFocusable(true);
        form.addView(dataSize, Ui.rowParams(this));

        mOptsBtn = Ui.styledPrimary(this, R.string.options_btn);
        mOptsBtn.setOnClickListener(
                v -> startActivity(
                        new Intent(this, OptionsGroupActivity.class)));
        form.addView(mOptsBtn, Ui.rowParams(this));

        Button go = findViewById(R.id.primary);
        go.setText(R.string.next_btn);
        Ui.focusFx(modeBtn);
        Ui.focusFx(go);
        final CheckBox dataImgF = dataImg;
        final EditText dataSizeF = dataSize;
        go.setOnClickListener(
                v -> {
                    int size = 0;
                    try {
                        size =
                                Integer.parseInt(
                                        dataSizeF.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    Intent i;
                    java.util.ArrayList<String> foundOses =
                            getIntent().getStringArrayListExtra(
                                    ScanActivity.EXTRA_OS);
                    if (mModeIdx == 0) {
                        i = new Intent(this, ProgressActivity.class);
                        i.putExtra(DiskActivity.EXTRA_DISK, disk);
                        i.putExtra(EXTRA_MODE, "disk");
                        if (foundOses != null) {
                            i.putStringArrayListExtra(
                                    ScanActivity.EXTRA_OS, foundOses);
                        }
                    } else {
                        i = new Intent(this, PartitionActivity.class);
                        i.putExtra(DiskActivity.EXTRA_DISK, disk);
                        i.putExtra(EXTRA_MODE, modeValues[mModeIdx]);
                        java.util.ArrayList<String> scan =
                                getIntent().getStringArrayListExtra(
                                        ScanActivity.EXTRA_SCAN);
                        if (scan != null) {
                            i.putStringArrayListExtra(
                                    ScanActivity.EXTRA_SCAN, scan);
                        }
                        if (foundOses != null) {
                            i.putStringArrayListExtra(
                                    ScanActivity.EXTRA_OS, foundOses);
                        }
                    }
                    i.putExtra(EXTRA_DATA_IMG, dataImgF.isChecked());
                    i.putExtra(EXTRA_DATA_SIZE, size);
                    i.putExtra(EXTRA_OPTS, OptionsStore.all());
                    startActivity(i);
                });
        modeBtn.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mOptsBtn != null) {
            int n = OptionsStore.count();
            mOptsBtn.setText(
                    getString(R.string.options_btn)
                            + (n > 0 ? " (" + n + ")" : ""));
        }
    }

    private CheckBox option(LinearLayout form, int textId, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(textId);
        c.setChecked(checked);
        c.setFocusable(true);
        c.setTextSize(20);
        form.addView(c, Ui.rowParams(this));
        return c;
    }
}
