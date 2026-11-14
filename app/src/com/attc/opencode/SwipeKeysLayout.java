package com.attc.opencode;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * Container for the two extra-key rows.
 *
 * A horizontal swipe over the row (left or right) toggles Termux's text-input
 * mode, where the IME composes a whole line with dictionary/autocorrect before
 * it is sent to the PTY. Taps on individual keys are left untouched: the
 * gesture is only intercepted once it is clearly horizontal and longer than the
 * system touch slop.
 */
public final class SwipeKeysLayout extends LinearLayout {

    public interface Listener {
        void onSwipe();
    }

    private final int slop;
    private Listener listener;
    private float downX;
    private float downY;
    private boolean swiping;

    public SwipeKeysLayout(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setSwipeListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) {
                    listener.onSwipe();
                }
            }
            swiping = false;
        } else if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            swiping = false;
        }
        return true;
    }
}
