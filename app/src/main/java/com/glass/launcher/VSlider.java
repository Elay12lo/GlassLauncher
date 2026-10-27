package com.glass.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/** Vertical pill slider in the control-center style (brightness / volume). */
public class VSlider extends View {
    public interface Listener { void onProgress(float p); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private final Drawable icon;
    private final Listener listener;
    private float progress;

    public VSlider(Context ctx, Drawable icon, float initial, Listener l) {
        super(ctx);
        this.icon = icon;
        this.listener = l;
        this.progress = Math.max(0f, Math.min(1f, initial));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, w / 2f, w / 2f, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        float top = h * (1f - progress);
        paint.setShader(new LinearGradient(0, top, 0, h, 0xFFFFFFFF, 0xFFE6ECF7, Shader.TileMode.CLAMP));
        c.drawRect(0, top, w, h, paint);
        paint.setShader(null);
        c.restore();
        int s = (int) (w * 0.46f);
        int cx = (int) (w / 2f);
        int cy = (int) (h - w * 0.5f);
        icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        icon.draw(c);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                set(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    private void set(float y) {
        if (getHeight() == 0) return;
        progress = Math.max(0f, Math.min(1f, 1f - y / getHeight()));
        invalidate();
        listener.onProgress(progress);
    }
}
