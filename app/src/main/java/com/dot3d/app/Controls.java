package com.dot3d.app;

import android.view.MotionEvent;

/**
 * Touch controls:
 *  - Left half of the screen: virtual joystick (walk). Appears where the finger lands.
 *  - Right half: drag to look (yaw/pitch); a jump button sits in the lower-right corner.
 * Left and right pointers are tracked independently for true multitouch.
 */
public final class Controls {
    public float moveX = 0f, moveY = 0f; // joystick output [-1,1]
    public float lookDX = 0f, lookDY = 0f; // consumed each frame
    public boolean jumpRequested = false;

    private int screenW, screenH;
    private final float joyRadius;
    private final float jumpBtnR;

    private int leftPointer = -1;
    private float joyCx, joyCy;

    private int rightPointer = -1;
    private float lastRx, lastRy;

    public boolean joyActive = false;
    public float joyCxOut, joyCyOut, joyKnobX, joyKnobY;

    public Controls(float density) {
        this.joyRadius = 120 * density;
        this.jumpBtnR = 70 * density;
    }

    public void setSize(int w, int h) { screenW = w; screenH = h; }

    private boolean inJumpButton(float x, float y) {
        float bx = screenW - jumpBtnR - 40;
        float by = screenH - jumpBtnR - 40;
        float dx = x - bx, dy = y - by;
        return dx * dx + dy * dy <= jumpBtnR * jumpBtnR;
    }

    public void onTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pi = e.getActionIndex();
                int id = e.getPointerId(pi);
                float x = e.getX(pi), y = e.getY(pi);
                if (x < screenW / 2f) {
                    if (leftPointer == -1) {
                        leftPointer = id;
                        joyCx = x; joyCy = y;
                        joyActive = true; joyCxOut = x; joyCyOut = y; joyKnobX = x; joyKnobY = y;
                    }
                } else {
                    if (inJumpButton(x, y)) {
                        jumpRequested = true;
                    } else if (rightPointer == -1) {
                        rightPointer = id;
                        lastRx = x; lastRy = y;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (id == leftPointer) {
                        float dx = x - joyCx, dy = y - joyCy;
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        float cl = Math.min(len, joyRadius);
                        float nx = len > 1e-3f ? dx / len : 0;
                        float ny = len > 1e-3f ? dy / len : 0;
                        moveX = nx * (cl / joyRadius);
                        moveY = ny * (cl / joyRadius);
                        joyKnobX = joyCx + nx * cl; joyKnobY = joyCy + ny * cl;
                    } else if (id == rightPointer) {
                        lookDX += (x - lastRx);
                        lookDY += (y - lastRy);
                        lastRx = x; lastRy = y;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int pi = e.getActionIndex();
                int id = e.getPointerId(pi);
                if (id == leftPointer) {
                    leftPointer = -1; moveX = 0; moveY = 0; joyActive = false;
                } else if (id == rightPointer) {
                    rightPointer = -1;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                leftPointer = -1; rightPointer = -1; moveX = 0; moveY = 0; joyActive = false;
                break;
            }
        }
    }

    /** Consume accumulated look delta. */
    public float[] consumeLook() {
        float[] r = new float[] { lookDX, lookDY };
        lookDX = 0; lookDY = 0;
        return r;
    }

    public boolean consumeJump() {
        boolean j = jumpRequested; jumpRequested = false; return j;
    }

    public float jumpButtonX() { return screenW - jumpBtnR - 40; }
    public float jumpButtonY() { return screenH - jumpBtnR - 40; }
    public float jumpButtonR() { return jumpBtnR; }
}