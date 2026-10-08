package org.los.tv.installer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Disk bar like Calamares PartitionBarsView: the whole disk as one
 * strip, proportional colored segments with names+sizes. Pure
 * visualization (not focusable, except the AFTER bar which acts as
 * the divider slider); selection happens on the rows.
 */
public class DiskBarView extends View {
    public static final class Seg {
        public final long startMb;
        public final long endMb;
        public final int color;
        public final String label;
        /** Hatched overlay (new partitions): reads instantly. */
        public boolean hatch;
        /** Used head overlay end, or -1. Drawn gapless inside the block. */
        public long usedEndMb = -1;

        public Seg(long startMb, long endMb, int color, String label) {
            this.startMb = startMb;
            this.endMb = endMb;
            this.color = color;
            this.label = label;
        }
    }

    private static final int[] PART_COLORS = {
            0xFF476093, 0xFF793A7F, 0xFF8E562A, 0xFF479392,
            0xFF7C3030, 0xFF77831A, 0xFF5A6E8C, 0xFF8C5A6E,
    };
    public static final int COLOR_FREE = 0xFF2A343C;
    public static final int COLOR_NEW = 0xFF2E9E5B;
    public static final int COLOR_USED = 0xFF455A64;
    public static final int COLOR_REST = 0xFF757575;
    private static final int COLOR_TRACK = 0xFF161E24;
    private static final int COLOR_FOCUS = 0xFF8AB4F8;

    private final List<Seg> mSegs = new ArrayList<>();
    private long mTotalMb = 1;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFrame = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DiskBarView(Context ctx) {
        super(ctx);
        init();
    }

    public DiskBarView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        setFocusable(false);
        float d = getResources().getDisplayMetrics().density;
        mTrack.setColor(COLOR_TRACK);
        mHatch.setColor(0x59000000);
        mHatch.setStrokeWidth(2 * d);
        mText.setColor(0xFFE8EAED);
        mText.setTextSize(12 * d);
        mFrame.setColor(COLOR_FOCUS);
        mFrame.setStyle(Paint.Style.STROKE);
        mFrame.setStrokeWidth(2 * d);
    }

    public void setData(long totalMb, List<Seg> segs) {
        mTotalMb = totalMb;
        if (mTotalMb <= 0) {
            long max = 0;
            for (Seg s : segs) {
                max = Math.max(max, s.endMb);
            }
            mTotalMb = Math.max(1, max);
        }
        mSegs.clear();
        mSegs.addAll(segs);
        invalidate();
    }

    public static int colorFor(int index) {
        return PART_COLORS[index % PART_COLORS.length];
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = (int) (56 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(
                MeasureSpec.getSize(wSpec),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0) {
            post(() -> requestLayout());
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        // Pill track under everything.
        c.drawRoundRect(new RectF(0, 0, w, h), 14 * d, 14 * d, mTrack);
        if (mSegs.isEmpty()) {
            if (isFocused()) {
                mFrame.setStrokeWidth(2 * d);
                c.drawRoundRect(new RectF(1, 1, w - 1, h - 1),
                        14 * d, 14 * d, mFrame);
            }
            return;
        }
        float gap = 1.5f * d;
        float padV = 4 * d;
        float rad = 8 * d;
        float x = 0;
        for (Seg s : mSegs) {
            float sw = (float) (s.endMb - s.startMb) / mTotalMb * w;
            if (sw < 1 && s.endMb > s.startMb) {
                sw = 1;
            }
            mFill.setColor(s.color);
            float left = Math.min(x + gap, x + sw);
            float right = Math.max(x + sw - gap, left);
            RectF r = new RectF(left, padV, right, h - padV);
            c.drawRoundRect(r, rad, rad, mFill);
            if (s.usedEndMb > s.startMb && s.usedEndMb < s.endMb) {
                float ux = x + (float) (s.usedEndMb - s.startMb) / mTotalMb
                        * w;
                ux = Math.max(left, Math.min(ux, right));
                mFill.setColor(COLOR_USED);
                c.drawRoundRect(new RectF(left, padV, ux, h - padV),
                        rad, rad, mFill);
            }
            if (s.hatch && right - left > 4 * d) {
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, rad, rad,
                        android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                float step = 7 * d;
                float hh = h - 2 * padV;
                for (float dx = -hh; dx < (right - left) + hh; dx += step) {
                    c.drawLine(left + dx, h - padV, left + dx + hh, padV,
                            mHatch);
                }
                c.restore();
            }
            String label = s.label;
            float avail = right - left;
            if (label != null && !label.isEmpty()
                    && mText.measureText(label) < avail - 8 * d) {
                c.drawText(label, left + 4 * d,
                        h / 2f - (mText.descent() + mText.ascent()) / 2, mText);
            }
            x += sw;
        }
        if (isFocused()) {
            c.drawRoundRect(new RectF(1, 1, w - 1, h - 1),
                    14 * d, 14 * d, mFrame);
        }
    }
}
