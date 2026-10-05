package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Partition picker, SetupWizard TV look. */
public class PartitionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_list);
        final String disk = getIntent().getStringExtra(DiskActivity.EXTRA_DISK);
        final Intent src = getIntent();

        ((TextView) findViewById(R.id.title))
                .setText(getString(R.string.part_title, disk));
        TextView sub = findViewById(R.id.subtitle);
        sub.setVisibility(android.view.View.VISIBLE);
        sub.setText(R.string.part_warn);
        findViewById(R.id.primary).setVisibility(android.view.View.GONE);

        LinearLayout list = findViewById(R.id.list);
        List<String> parts = partitionsOf(disk);
        boolean first = true;
        for (String row : parts) {
            final String dev = row.split("\\s+")[0];
            Button b = MainActivity.rowButton(this, row);
            b.setOnClickListener(
                    v -> {
                        Intent i = new Intent(this, ProgressActivity.class);
                        i.putExtra(DiskActivity.EXTRA_DISK, dev);
                        i.putExtra(ModeActivity.EXTRA_MODE, "part");
                        copyOpts(src, i);
                        startActivity(i);
                    });
            list.addView(b, Ui.rowParams(this));
            if (first) {
                b.requestFocus();
                first = false;
            }
        }
    }

    private void copyOpts(Intent src, Intent dst) {
        dst.putExtra(
                ModeActivity.EXTRA_DATA_IMG,
                src.getBooleanExtra(ModeActivity.EXTRA_DATA_IMG, false));
        dst.putExtra(
                ModeActivity.EXTRA_DATA_SIZE,
                src.getIntExtra(ModeActivity.EXTRA_DATA_SIZE, 0));
        dst.putExtra(
                ModeActivity.EXTRA_OPTS,
                src.getStringExtra(ModeActivity.EXTRA_OPTS));
    }

    private List<String> partitionsOf(String disk) {
        List<String> out = new ArrayList<>();
        String base = disk.replace("/dev/block/", "");
        Shell.Result r = Shell.sh("ls /sys/block/" + base + "/ 2>/dev/null");
        if (!r.ok()) {
            return out;
        }
        for (String e : r.out.split("\\s+")) {
            e = e.trim();
            if (e.startsWith(base) && !e.equals(base) && !e.contains("..")) {
                String dev = "/dev/block/" + e;
                Shell.Result s =
                        Shell.sh(
                                "blkid -s TYPE -o value " + dev
                                        + " 2>/dev/null; blkid -s LABEL -o value "
                                        + dev + " 2>/dev/null");
                String info = s.ok() ? s.out.trim().replace("\n", " ") : "";
                out.add(dev + (info.isEmpty() ? "" : "  [" + info + "]"));
            }
        }
        return out;
    }
}
