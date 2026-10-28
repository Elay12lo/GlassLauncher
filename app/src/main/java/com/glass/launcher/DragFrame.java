package com.glass.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Cell container: long press starts a drag and all further touches are forwarded to the host. */
class DragFrame extends FrameLayout {
    interface Host {
        void onLongPress(DragFrame f, float rawX, float rawY);
        void onDragEvent(MotionEvent e);
    }

    private final Host host;
    private final int slop;
    private boolean armed, active;
    private float downX, downY, lastX, lastY;

    private final Runnable fire = new Runnable() {
        @Override public void run() {
            if (!armed) return;
            armed = false;
            active = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            host.onLongPress(DragFrame.this, lastX, lastY);
        }
    };

    DragFrame(Context c, Host host) {
        super(c);
        this.host = host;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
    }

    private void track(MotionEvent e) {
        lastX = e.getRawX();
        lastY = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (armed && (Math.abs(lastX - downX) > slop || Math.abs(lastY - downY) > slop)) {
                    armed = false;
                    removeCallbacks(fire);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                armed = false;
                removeCallbacks(fire);
                break;
            default:
                break;
        }
    }

    private void deliver(MotionEvent e) {
        lastX = e.getRawX();
        lastY = e.getRawY();
        host.onDragEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) active = false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            armed = true;
            active = false;
            downX = e.getRawX();
            downY = e.getRawY();
            lastX = downX;
            lastY = downY;
            postDelayed(fire, ViewConfiguration.getLongPressTimeout());
            return false;
        }
        if (active) {
            deliver(e);
            return true;
        }
        track(e);
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (active) {
            deliver(e);
            return true;
        }
        track(e);
        return super.onTouchEvent(e);
    }
}
