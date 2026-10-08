package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Partition picker. Part mode lists partitions to format; alongside
 * mode reuses the scan screen map (no second scan): target rows +
 * before/after disk bars, the AFTER bar itself is the divider slider.
 */
public class PartitionActivity extends Activity {
    private static class Part {
        String dev;
        long startMb;
        long endMb;
        String fstype = "";
        String label = "";
        String os = "";
        long usedMb = -1;
    }

    private static class Free {
        long startMb;
        long endMb;
    }

    // Shrink candidates below this are noise (boot/ESP/MSR holders):
    // the system cannot fit there anyway.
    private static final long MIN_SHOW_MB = 5120;

    private final List<Part> mParts = new ArrayList<>();
    private final List<Free> mFrees = new ArrayList<>();
    private long mDiskMb;
    private String mDisk = "";
    private Part mSelPart;
    private long mSelFree = -1;
    private long mAsizeMb = 20480;
    private long mMaxMb = 20480;
    // Default target: fill the hole, top up to 32G from the partition;
    // a hole over 32G is taken whole. User slider moves override it.
    private static final long DEF_TARGET_MB = 32768;
    private boolean mUserSized;
    // Bounds, both from the scan, nothing hardcoded:
    // floor = payload minimum (fits the system),
    // ceiling = what the target yields (free room or shrinkable rest).
    private long mFloorMb = 1024;
    private long mPayloadMinMb = 0;
    private long mLastStep;
    private DiskBarView mBeforeBar;
    private TextView mBeforeLabel;
    private DiskBarView mAfterBar;
    private TextView mAfterLabel;
    private Intent mSrc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_list);
        final String disk = getIntent().getStringExtra(DiskActivity.EXTRA_DISK);
        mDisk = disk;
        final Intent src = getIntent();
        mSrc = src;
        final String mode = src.getStringExtra(ModeActivity.EXTRA_MODE);
        final boolean alongside = "alongside".equals(mode);

        ((TextView) findViewById(R.id.title))
                .setText(getString(R.string.part_title, disk));
        TextView sub = findViewById(R.id.subtitle);
        sub.setVisibility(android.view.View.VISIBLE);
        sub.setText(alongside ? R.string.part_alongside_warn : R.string.part_warn);
        findViewById(R.id.primary).setVisibility(android.view.View.GONE);

        LinearLayout list = findViewById(R.id.list);
        if (!alongside) {
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
            return;
        }
        java.util.ArrayList<String> cached =
                getIntent().getStringArrayListExtra(ScanActivity.EXTRA_SCAN);
        if (cached != null && !cached.isEmpty()) {
            renderAlongside(list, cached);
            return;
        }
        TextView note = new TextView(this);
        note.setText(R.string.part_scanning);
        note.setTextSize(20);
        list.addView(note, Ui.rowParams(this));
        new Thread(() -> {
            List<String> lines = Engine.scan(this);
            runOnUiThread(() -> renderAlongside(list, lines));
        }).start();
    }

    private void renderAlongside(LinearLayout list, List<String> lines) {
        list.removeAllViews();
        parseScan(lines);
        TextView diag = new TextView(this);
        diag.setTextSize(18);
        if (lines.isEmpty()) {
            diag.setText(R.string.part_scan_empty);
        } else {
            diag.setText(getString(R.string.part_scan_found,
                    mParts.size(), mFrees.size()));
        }
        list.addView(diag, Ui.rowParams(this));
        for (Part p : mParts) {
            if (p.endMb - p.startMb < MIN_SHOW_MB) {
                continue;
            }
            Button b = MainActivity.rowButton(this, describe(p));
            b.setOnClickListener(v -> {
                mSelPart = p;
                mSelFree = -1;
                refreshAlongside();
                b.requestFocus();
            });
            list.addView(b, Ui.rowParams(this));
        }
        for (Free f : mFrees) {
            if (f.endMb - f.startMb < MIN_SHOW_MB) {
                continue;
            }
            long gb = (f.endMb - f.startMb) / 1024;
            Button b = MainActivity.rowButton(this,
                    getString(R.string.part_free_space) + "  " + gb + " GB");
            final long start = f.startMb;
            b.setOnClickListener(v -> {
                mSelPart = null;
                mSelFree = start;
                refreshAlongside();
                b.requestFocus();
            });
            list.addView(b, Ui.rowParams(this));
        }
        mBeforeLabel = legendText(list);
        mBeforeBar = diskBar(list);
        // The AFTER bar IS the slider: focused, LEFT/RIGHT reshapes
        // NEW vs shrunk, hold slides continuously.
        mAfterBar = diskBar(list);
        mAfterBar.setFocusable(true);
        mAfterBar.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                return false;
            }
            long dir = 0;
            // Arrows move the divider itself: LEFT pushes it left, NEW
            // grows (it eats from the tail right-to-left); RIGHT pulls
            // it back. PLUS/MINUS stay magnitude keys.
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                dir = 1024;
            } else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                dir = -1024;
            } else if (code == android.view.KeyEvent.KEYCODE_MINUS) {
                dir = -1024;
            } else if (code == android.view.KeyEvent.KEYCODE_PLUS
                    || code == android.view.KeyEvent.KEYCODE_EQUALS) {
                dir = 1024;
            } else {
                return false;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (ev.getRepeatCount() > 0 && now - mLastStep < 120) {
                return true;
            }
            mLastStep = now;
            setAsizeMb(mAsizeMb + dir);
            return true;
        });
        mAfterLabel = legendText(list);
        Button go = findViewById(R.id.primary);
        go.setVisibility(android.view.View.VISIBLE);
        go.setText(R.string.next_btn);
        Ui.focusFx(go);
        // A single suitable partition needs no picking: preselect it
        // and hand focus straight to the slider bar.
        int fitParts = 0;
        Part onlyPart = null;
        for (Part p : mParts) {
            if (p.endMb - p.startMb >= MIN_SHOW_MB) {
                fitParts++;
                onlyPart = p;
            }
        }
        int fitFrees = 0;
        for (Free f : mFrees) {
            if (f.endMb - f.startMb >= MIN_SHOW_MB) {
                fitFrees++;
            }
        }
        if (fitParts == 1 && fitFrees == 0) {
            mSelPart = onlyPart;
            mSelFree = -1;
            refreshAlongside();
            mAfterBar.requestFocus();
        } else {
            refreshAlongside();
            for (int i = 0; i < list.getChildCount(); i++) {
                android.view.View ch = list.getChildAt(i);
                if (ch instanceof Button) {
                    ch.requestFocus();
                    break;
                }
            }
        }
        go.setOnClickListener(v -> {
            Intent i = new Intent(this, ProgressActivity.class);
            i.putExtra(DiskActivity.EXTRA_DISK, mDisk);
            i.putExtra(ModeActivity.EXTRA_MODE, "alongside");
            if (mSelPart != null) {
                i.putExtra("apart", mSelPart.dev);
            } else {
                i.putExtra("afree", mSelFree);
            }
            i.putExtra(ModeActivity.EXTRA_ASIZE, (int) mAsizeMb);
            copyOpts(mSrc, i);
            startActivity(i);
        });
    }

    private DiskBarView diskBar(LinearLayout list) {
        DiskBarView v = new DiskBarView(this);
        list.addView(v, Ui.rowParams(this));
        return v;
    }

    private TextView legendText(LinearLayout list) {
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setFocusable(false);
        list.addView(t, Ui.rowParams(this));
        return t;
    }

    private void refreshAlongside() {
        // Ceiling: what the target yields. Shrink keeps used + 512 on
        // the old system; free room gives it all. An adjacent hole is
        // eaten first, so it extends the ceiling — but carving past
        // (used + 512) is never allowed.
        long usable = 0;
        if (mSelPart != null) {
            long partLen = mSelPart.endMb - mSelPart.startMb;
            if (mSelPart.usedMb >= 0) {
                usable = partLen - mSelPart.usedMb - 512;
            } else {
                usable = partLen - 1024;
            }
            for (Free f : mFrees) {
                if (Math.abs(f.startMb - mSelPart.endMb) <= 2) {
                    usable += f.endMb - f.startMb;
                    break;
                }
            }
            if (usable < 0) {
                usable = 0;
            }
        } else if (mSelFree >= 0) {
            for (Free f : mFrees) {
                if (f.startMb == mSelFree) {
                    usable = f.endMb - f.startMb;
                    break;
                }
            }
        } else if (!mFrees.isEmpty()) {
            long big = 0;
            for (Free f : mFrees) {
                big = Math.max(big, f.endMb - f.startMb);
            }
            usable = big;
        }
        // Floor is the payload minimum (fits the system).
        mFloorMb = mPayloadMinMb > 0 ? mPayloadMinMb : 1024;
        mMaxMb = usable;
        if (!mUserSized) {
            mAsizeMb = defaultAsizeMb(usable);
        }
        if (mAsizeMb > mMaxMb) {
            mAsizeMb = mMaxMb;
        }
        if (mAsizeMb < mFloorMb) {
            mAsizeMb = Math.min(mFloorMb, mMaxMb);
        }
        drawBars();
    }

    /** Default size: whole hole, topped up to 32G when it is smaller. */
    private long defaultAsizeMb(long usable) {
        long hole = 0;
        if (mSelPart != null) {
            for (Free f : mFrees) {
                if (Math.abs(f.startMb - mSelPart.endMb) <= 2) {
                    hole = f.endMb - f.startMb;
                    break;
                }
            }
        } else if (mSelFree >= 0) {
            for (Free f : mFrees) {
                if (f.startMb == mSelFree) {
                    hole = f.endMb - f.startMb;
                    break;
                }
            }
        } else {
            for (Free f : mFrees) {
                hole = Math.max(hole, f.endMb - f.startMb);
            }
        }
        if (hole > DEF_TARGET_MB) {
            return hole;
        }
        long total = Math.min(DEF_TARGET_MB, usable);
        return Math.max(total, 0);
    }

    private void setAsizeMb(long mb) {
        mUserSized = true;
        if (mb < mFloorMb) {
            mb = mFloorMb;
        }
        if (mb > mMaxMb) {
            mb = mMaxMb;
        }
        mAsizeMb = mb;
        drawAfter();
    }

    private void drawBars() {
        mBeforeBar.setData(mDiskMb, segments(null, 0));
        mBeforeLabel.setText(legend(null, 0));
        drawAfter();
    }

    private void drawAfter() {
        mAfterBar.setData(mDiskMb, segments(mAsizeMb, 1));
        mAfterLabel.setText(legend(mAsizeMb, 1));
    }

    /** Ordered segments for a bar. kind 0 = before, 1 = after (with NEW). */
    private java.util.List<DiskBarView.Seg> segments(Long asizeMb, int kind) {
        java.util.List<DiskBarView.Seg> out = new java.util.ArrayList<>();
        List<Part> parts = new ArrayList<>(mParts);
        List<Free> frees = new ArrayList<>(mFrees);
        Collections.sort(parts, Comparator.comparingLong(p -> p.startMb));
        Collections.sort(frees, Comparator.comparingLong(f -> f.startMb));
        // Mirror the engine: a free hole adjacent to the selected
        // partition's tail is eaten first, only the missing part is
        // carved from the partition — NEW spans tail + hole, it is NOT
        // drawn inside the old partition.
        long holeStart = -1;
        long holeEnd = -1;
        if (kind == 1 && mSelPart != null && asizeMb != null) {
            for (Free f : frees) {
                if (Math.abs(f.startMb - mSelPart.endMb) <= 2) {
                    holeStart = f.startMb;
                    holeEnd = f.endMb;
                    break;
                }
            }
        }
        int pi = 0;
        int fi = 0;
        int colorIdx = 0;
        while (pi < parts.size() || fi < frees.size()) {
            boolean takePart = fi >= frees.size()
                    || (pi < parts.size()
                            && parts.get(pi).startMb < frees.get(fi).startMb);
            if (takePart) {
                Part p = parts.get(pi++);
                String tag = shortName(p.dev) + " " + gb(p.endMb - p.startMb);
                if (kind == 1 && mSelPart != null
                        && p.dev.equals(mSelPart.dev) && asizeMb != null) {
                    long carve = asizeMb - (holeEnd - holeStart);
                    if (holeStart < 0 || carve < 0) {
                        carve = holeStart < 0 ? asizeMb : 0;
                    }
                    long ns = p.endMb - carve;
                    if (ns < p.startMb) {
                        ns = p.startMb;
                    }
                    long tailEnd = holeStart < 0 ? p.endMb : holeEnd;
                    // One solid kept block, used head as a gapless
                    // overlay (never two blocks for one partition).
                    DiskBarView.Seg kept = new DiskBarView.Seg(
                            p.startMb, ns, DiskBarView.COLOR_REST, tag);
                    long uh = p.startMb + usedOf(p);
                    if (uh > p.startMb) {
                        kept.usedEndMb = Math.min(uh, ns);
                    }
                    out.add(kept);
                    DiskBarView.Seg fresh = new DiskBarView.Seg(ns, tailEnd,
                            DiskBarView.COLOR_NEW,
                            "NEW " + gb(tailEnd - ns));
                    fresh.hatch = true;
                    out.add(fresh);
                } else {
                    // One solid block per partition; used head as a
                    // gapless overlay so it never reads as two parts.
                    long len = p.endMb - p.startMb;
                    long used = usedOf(p);
                    if (used <= 0) {
                        out.add(new DiskBarView.Seg(p.startMb, p.endMb,
                                DiskBarView.COLOR_REST, tag));
                    } else if (used >= len) {
                        out.add(new DiskBarView.Seg(p.startMb, p.endMb,
                                DiskBarView.COLOR_USED, tag));
                    } else {
                        DiskBarView.Seg one = new DiskBarView.Seg(
                                p.startMb, p.endMb, DiskBarView.COLOR_REST,
                                tag);
                        one.usedEndMb = p.startMb + used;
                        out.add(one);
                    }
                }
                colorIdx++;
            } else {
                Free f = frees.get(fi++);
                if (kind == 1 && f.startMb == holeStart) {
                    // Eaten by the NEW span above: not drawn twice.
                    continue;
                }
                if (kind == 1 && mSelPart == null && mSelFree >= 0
                        && f.startMb == mSelFree && asizeMb != null) {
                    long ne = f.startMb + asizeMb;
                    if (ne > f.endMb) {
                        ne = f.endMb;
                    }
                    DiskBarView.Seg fresh = new DiskBarView.Seg(f.startMb, ne,
                            DiskBarView.COLOR_NEW, "NEW " + gb(ne - f.startMb));
                    fresh.hatch = true;
                    out.add(fresh);
                    if (ne < f.endMb) {
                        out.add(new DiskBarView.Seg(ne, f.endMb,
                                DiskBarView.COLOR_FREE, gb(f.endMb - ne)));
                    }
                } else {
                    out.add(new DiskBarView.Seg(f.startMb, f.endMb,
                            DiskBarView.COLOR_FREE, gb(f.endMb - f.startMb)));
                }
            }
        }
        return out;
    }

    /** Used MB clamped to [0, len], or 0 when unknown. */
    private static long usedOf(Part p) {
        if (p.usedMb < 0) {
            return 0;
        }
        long len = p.endMb - p.startMb;
        if (p.usedMb > len) {
            return len;
        }
        return p.usedMb;
    }

    private String legend(Long asizeMb, int kind) {
        StringBuilder b = new StringBuilder(kind == 0 ? "BEFORE" : "AFTER");
        for (Part p : mParts) {
            // Rows below the size filter are not candidates: the legend
            // must not advertise them either.
            if (p.endMb - p.startMb < MIN_SHOW_MB) {
                continue;
            }
            b.append("\n").append(shortName(p.dev)).append(": ")
                    .append(gb(p.endMb - p.startMb));
            if (!p.label.isEmpty() && !"_".equals(p.label)) {
                b.append(" ").append(p.label);
            }
            if (!p.os.isEmpty()) {
                b.append(" [").append(p.os).append("]");
            }
            if (kind == 1 && mSelPart != null && p.dev.equals(mSelPart.dev)
                    && asizeMb != null) {
                long holeHere = 0;
                for (Free f : mFrees) {
                    if (Math.abs(f.startMb - p.endMb) <= 2) {
                        holeHere = f.endMb - f.startMb;
                        break;
                    }
                }
                long carve = asizeMb - holeHere;
                if (carve < 0) {
                    carve = 0;
                }
                b.append(" -> ").append(gb(p.endMb - p.startMb - carve))
                        .append(" + NEW ").append(gb(asizeMb))
                        .append(" (carve ").append(gb(carve))
                        .append(" + hole ").append(gb(holeHere)).append(")");
            }
        }
        // Holes are unpartitioned disk, not free room inside a
        // partition: listed separately so the two never mix. In AFTER
        // the hole eaten by NEW is skipped.
        for (Free f : mFrees) {
            if (kind == 1 && mSelPart != null
                    && Math.abs(f.startMb - mSelPart.endMb) <= 2) {
                continue;
            }
            b.append("\n").append("free: ").append(gb(f.endMb - f.startMb));
        }
        return b.toString();
    }

    private static String shortName(String dev) {
        return dev.replace("/dev/block/", "");
    }

    private static String gb(long mb) {
        if (mb >= 1024) {
            long g = mb / 1024;
            long rest = (mb % 1024) / 100;
            return rest > 0 ? g + "." + rest + "G" : g + "G";
        }
        return mb + "M";
    }

    private String describe(Part p) {
        long sizeGb = (p.endMb - p.startMb) / 1024;
        String s = p.dev + "  " + sizeGb + " GB  " + p.fstype;
        if (!p.label.isEmpty() && !"_".equals(p.label)) {
            s += "  " + p.label;
        }
        if (!p.os.isEmpty()) {
            s += "  [" + p.os + "]";
        }
        if (p.usedMb >= 0) {
            s += "  used " + gb(p.usedMb);
        }
        return s;
    }

    private void parseScan(List<String> lines) {
        mParts.clear();
        mFrees.clear();
        mDiskMb = 0;
        List<String> osLines = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\\s+");
            if (f.length < 2) {
                continue;
            }
            if ("DISK".equals(f[0]) && f.length >= 3
                    && f[1].equals(mDisk)) {
                try {
                    mDiskMb = Long.parseLong(f[2]);
                } catch (NumberFormatException ignored) {
                }
            } else if ("PART".equals(f[0]) && f.length >= 5) {
                String dp = mDisk.replace("/dev/block/", "");
                String pd = f[1].replace("/dev/block/", "");
                if (!pd.startsWith(dp) && !f[1].equals(mDisk)) {
                    String pfx = dp.replaceAll("\\d+$", "");
                    if (!pd.startsWith(pfx)) {
                        continue;
                    }
                }
                Part p = new Part();
                p.dev = f[1];
                try {
                    p.startMb = Long.parseLong(f[2]);
                    p.endMb = Long.parseLong(f[3]);
                } catch (NumberFormatException e) {
                    continue;
                }
                p.fstype = f[4];
                if (f.length > 5) {
                    StringBuilder lb = new StringBuilder();
                    for (int i = 5; i < f.length; i++) {
                        if (lb.length() > 0) {
                            lb.append(' ');
                        }
                        lb.append(f[i]);
                    }
                    p.label = lb.toString();
                }
                mParts.add(p);
            } else if ("FREE".equals(f[0]) && f.length >= 4
                    && f[1].equals(mDisk)) {
                Free fr = new Free();
                try {
                    fr.startMb = Long.parseLong(f[2]);
                    fr.endMb = Long.parseLong(f[3]);
                } catch (NumberFormatException e) {
                    continue;
                }
                mFrees.add(fr);
            } else if ("OS".equals(f[0])) {
                osLines.add(line.substring(3));
            } else if ("USE".equals(f[0]) && f.length >= 3) {
                for (Part p : mParts) {
                    if (p.dev.equals(f[1])) {
                        try {
                            p.usedMb = Long.parseLong(f[2]);
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    }
                }
            } else if ("MIN".equals(f[0]) && f.length >= 2) {
                try {
                    mPayloadMinMb = Long.parseLong(f[1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (Part p : mParts) {
            for (String o : osLines) {
                String[] of = o.split("\\|", -1);
                if (of.length >= 4 && of[3].equals(p.dev)) {
                    p.os = of[1];
                    break;
                }
            }
        }
        Collections.sort(mParts, Comparator.comparingLong(p -> p.startMb));
        Collections.sort(mFrees, Comparator.comparingLong(f -> f.startMb));
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
        java.util.ArrayList<String> oses =
                src.getStringArrayListExtra(ScanActivity.EXTRA_OS);
        if (oses != null && !oses.isEmpty()) {
            dst.putStringArrayListExtra(ScanActivity.EXTRA_OS, oses);
        }
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
