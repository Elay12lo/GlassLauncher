package com.glass.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Translucent "liquid glass" surface: soft gradient, top sheen and a bright diagonal rim. */
public class GlassDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float stroke;
    private final int top;
    private final int bottom;

    public GlassDrawable(float radius, float stroke, int top, int bottom) {
        this.radius = radius;
        this.stroke = stroke;
        this.top = top;
        this.bottom = bottom;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xB3FFFFFF, 0x14FFFFFF, 0x66FFFFFF}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float in = stroke / 2f;
        rect.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        c.drawRoundRect(rect, r, r, fill);
        c.drawRoundRect(rect, r, r, sheen);
        c.drawRoundRect(rect, r, r, rim);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); sheen.setAlpha(alpha); rim.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
