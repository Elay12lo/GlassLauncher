package com.glass.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple monochrome vector symbols drawn on a 100x100 grid (control-center style). */
public class Glyph extends Drawable {
    public static final int WIFI = 0, BLUETOOTH = 1, AIRPLANE = 2, HOTSPOT = 3, SUN = 4,
            SPEAKER = 5, BELL = 6, LOCKROT = 7, MOON = 8, FLASH = 9, TIMER = 10, CALC = 11,
            CAMERA = 12, GEAR = 13, BATTERY = 14, CAST = 15, PLAY = 16, PAUSE = 17,
            PREV = 18, NEXT = 19, SEARCH = 20;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Glyph(int type, int color) {
        this.type = type;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    private void fill() { p.setStyle(Paint.Style.FILL); }
    private void line(float w) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); }

    private void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        line(7f);
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, p);
    }

    private Path poly(float... v) {
        Path path = new Path();
        path.moveTo(v[0], v[1]);
        for (int i = 2; i < v.length; i += 2) path.lineTo(v[i], v[i + 1]);
        path.close();
        return path;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float side = Math.min(b.width(), b.height());
        float sc = side / 100f;
        if (sc <= 0f) return;
        c.save();
        c.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
        c.scale(sc, sc);
        switch (type) {
            case WIFI:
                for (float r : new float[]{56f, 38f, 20f}) arc(c, 50, 84, r, 225, 90);
                fill();
                c.drawCircle(50, 84, 6, p);
                break;
            case BLUETOOTH: {
                Path t = new Path();
                t.moveTo(28, 32); t.lineTo(72, 68); t.lineTo(50, 86);
                t.lineTo(50, 14); t.lineTo(72, 32); t.lineTo(28, 68);
                line(7f);
                c.drawPath(t, p);
                break;
            }
            case AIRPLANE: {
                c.rotate(45, 50, 50);
                c.scale(0.9f, 0.9f, 50, 50);
                Path a = new Path();
                a.moveTo(50, 8); a.cubicTo(55, 8, 57, 13, 57, 19); a.lineTo(57, 38);
                a.lineTo(90, 58); a.lineTo(90, 67); a.lineTo(57, 57); a.lineTo(57, 76);
                a.lineTo(68, 85); a.lineTo(68, 91); a.lineTo(50, 86); a.lineTo(32, 91);
                a.lineTo(32, 85); a.lineTo(43, 76); a.lineTo(43, 57); a.lineTo(10, 67);
                a.lineTo(10, 58); a.lineTo(43, 38); a.lineTo(43, 19);
                a.cubicTo(43, 13, 45, 8, 50, 8); a.close();
                fill();
                c.drawPath(a, p);
                break;
            }
            case HOTSPOT:
                for (float r : new float[]{40f, 26f}) {
                    arc(c, 50, 50, r, -50, 100);
                    arc(c, 50, 50, r, 130, 100);
                }
                fill();
                c.drawCircle(50, 50, 7, p);
                break;
            case SUN:
                fill();
                c.drawCircle(50, 50, 16, p);
                line(6f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(50 + 27 * cs, 50 + 27 * sn, 50 + 39 * cs, 50 + 39 * sn, p);
                }
                break;
            case SPEAKER:
                fill();
                c.drawPath(poly(10, 38, 28, 38, 48, 20, 48, 80, 28, 62, 10, 62), p);
                arc(c, 52, 50, 18, -45, 90);
                arc(c, 52, 50, 32, -45, 90);
                break;
            case BELL: {
                Path bell = new Path();
                bell.moveTo(50, 12); bell.cubicTo(34, 12, 28, 26, 28, 42); bell.lineTo(28, 56);
                bell.lineTo(18, 68); bell.lineTo(82, 68); bell.lineTo(72, 56); bell.lineTo(72, 42);
                bell.cubicTo(72, 26, 66, 12, 50, 12); bell.close();
                fill();
                c.drawPath(bell, p);
                c.drawCircle(50, 78, 8, p);
                line(8f);
                c.drawLine(18, 16, 84, 88, p);
                break;
            }
            case LOCKROT:
                arc(c, 50, 50, 38, 200, 300);
                fill();
                c.drawPath(poly(13f, 65f, 28f, 69f, 14f, 80f), p);
                c.drawRoundRect(new RectF(36, 48, 64, 70), 4, 4, p);
                line(6f);
                c.drawArc(new RectF(40, 32, 60, 56), 180, 180, false, p);
                break;
            case MOON: {
                Path m = new Path();
                m.addCircle(46, 52, 34, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(64, 38, 30, Path.Direction.CW);
                m.op(cut, Path.Op.DIFFERENCE);
                fill();
                c.drawPath(m, p);
                break;
            }
            case FLASH:
                fill();
                c.drawRoundRect(new RectF(32, 8, 68, 30), 6, 6, p);
                c.drawRoundRect(new RectF(38, 32, 62, 92), 8, 8, p);
                break;
            case TIMER:
                arc(c, 50, 56, 34, 0, 360);
                fill();
                c.drawRoundRect(new RectF(42, 8, 58, 16), 3, 3, p);
                line(7f);
                c.drawLine(50, 56, 62, 38, p);
                break;
            case CALC:
                line(6f);
                c.drawRoundRect(new RectF(22, 8, 78, 92), 12, 12, p);
                fill();
                c.drawRoundRect(new RectF(32, 20, 68, 36), 4, 4, p);
                for (int r = 0; r < 3; r++)
                    for (int k = 0; k < 3; k++) c.drawCircle(37 + k * 13, 50 + r * 14, 4.5f, p);
                break;
            case CAMERA: {
                int layer = c.saveLayer(null, null);
                fill();
                c.drawRoundRect(new RectF(10, 28, 90, 82), 12, 12, p);
                c.drawRoundRect(new RectF(32, 18, 52, 32), 4, 4, p);
                Paint clr = new Paint(Paint.ANTI_ALIAS_FLAG);
                clr.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                c.drawCircle(50, 55, 17, clr);
                c.restoreToCount(layer);
                fill();
                c.drawCircle(50, 55, 10, p);
                break;
            }
            case GEAR:
                line(10f);
                c.drawCircle(50, 50, 24, p);
                p.setStrokeWidth(13f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(50 + 32 * cs, 50 + 32 * sn, 50 + 40 * cs, 50 + 40 * sn, p);
                }
                break;
            case BATTERY:
                line(6f);
                c.drawRoundRect(new RectF(8, 30, 82, 70), 10, 10, p);
                fill();
                c.drawRoundRect(new RectF(85, 42, 93, 58), 3, 3, p);
                c.drawRoundRect(new RectF(15, 37, 42, 63), 5, 5, p);
                break;
            case CAST:
                line(6f);
                c.drawRoundRect(new RectF(10, 20, 66, 60), 8, 8, p);
                fill();
                c.drawRoundRect(new RectF(34, 44, 90, 80), 8, 8, p);
                break;
            case PLAY:
                fill();
                c.drawPath(poly(30, 16, 84, 50, 30, 84), p);
                break;
            case PAUSE:
                fill();
                c.drawRoundRect(new RectF(26, 18, 44, 82), 4, 4, p);
                c.drawRoundRect(new RectF(56, 18, 74, 82), 4, 4, p);
                break;
            case PREV:
                fill();
                c.drawPath(poly(50, 24, 14, 50, 50, 76), p);
                c.drawPath(poly(88, 24, 52, 50, 88, 76), p);
                break;
            case NEXT:
                fill();
                c.drawPath(poly(12, 24, 48, 50, 12, 76), p);
                c.drawPath(poly(50, 24, 86, 50, 50, 76), p);
                break;
            case SEARCH:
                line(9f);
                c.drawCircle(42, 42, 24, p);
                c.drawLine(60, 60, 86, 86, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
