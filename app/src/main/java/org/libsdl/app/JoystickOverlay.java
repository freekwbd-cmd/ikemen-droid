package org.libsdl.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.ikemen_engine.ikemen_go.R;

public class JoystickOverlay extends View {
    private int deviceId = 0, axisX = 0, axisY = 0;
    private float centerX, centerY, stickX, stickY, radius;
    // Analog deadzone as a fraction of full deflection. Inside it the stick
    // reports neutral; outside, the response is rescaled so full deflection
    // still reaches 1.0 (no loss of range).
    private float deadzone = 0.15f;
    // Extra capture area around the base (1.0 = view bounds exactly).
    private float grabLeniency = 1.75f;

    public JoystickOverlay(Context context) {
        super(context);
    }

    public JoystickOverlay(Context context, int deviceId, int axisX, int axisY) {
        super(context);
        this.deviceId = deviceId;
        this.axisX = axisX;
        this.axisY = axisY;
    }

    public JoystickOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        initFromAttrs(context, attrs);
    }

    public JoystickOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initFromAttrs(context, attrs);
    }

    private void initFromAttrs(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.JoystickOverlay);
            try {
                // The second parameter is the default value if the attribute isn't found
                axisX = a.getInt(R.styleable.JoystickOverlay_axisX, 0);
                axisY = a.getInt(R.styleable.JoystickOverlay_axisY, 1);
            } finally {
                a.recycle();
            }
        }
    }

    public void setAttrs(int deviceId, int axisX, int axisY) {
        this.deviceId = deviceId;
        this.axisX = axisX;
        this.axisY = axisY;
    }

    public void setDeadzone(float dz) {
        deadzone = Math.max(0f, Math.min(0.5f, dz));
    }

    public void setGrabLeniency(float g) {
        grabLeniency = Math.max(1f, Math.min(3f, g));
    }

    public float getGrabLeniency() {
        return grabLeniency;
    }

    // Applies a rounded deadzone to the raw normalized vector.
    private void applyDeadzone(float[] v) {
        float mag = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        if (mag <= deadzone) {
            v[0] = 0f;
            v[1] = 0f;
            return;
        }
        float scaled = (mag - deadzone) / (1f - deadzone);
        v[0] = v[0] / mag * scaled;
        v[1] = v[1] / mag * scaled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Provide a default size (200px) if none is specified
        int defSize = 200;
        int w = resolveSize(defSize, widthMeasureSpec);
        int h = resolveSize(defSize, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 3.0f;
        stickX = centerX;
        stickY = centerY;
    }

    public void updateVisualPos(float x, float y) {
        this.stickX = x;
        this.stickY = y;
        invalidate();
    }

    public void sendToSDL(float normX, float normY) {
        if (isInEditMode()) return;
        float[] v = new float[]{normX, normY};
        applyDeadzone(v);
        SDLControllerManager.onNativeJoy(deviceId, axisX, v[0]);
        SDLControllerManager.onNativeJoy(deviceId, axisY, v[1]);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // If we're in the editor, ensure we have values even if onSizeChanged didn't fire
        if (radius <= 0) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return; // Still nothing to draw

            centerX = w / 2f;
            centerY = h / 2f;
            radius = Math.min(w, h) / 3.0f;
            stickX = centerX;
            stickY = centerY;
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Iron Yagami: dark iron, crimson ring, ember glow core
        // Base: very transparent so the game shows through
        p.setColor(0x33101010);
        canvas.drawCircle(centerX, centerY, radius, p);
        // Thin sleek crimson ring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(radius * 0.035f);
        p.setColor(0xCCFF2222);
        canvas.drawCircle(centerX, centerY, radius * 0.97f, p);
        // Subtle inner ring
        p.setStrokeWidth(radius * 0.02f);
        p.setColor(0x66FF2222);
        canvas.drawCircle(centerX, centerY, radius * 0.55f, p);
        p.setStyle(Paint.Style.FILL);

        // Knob: dark iron with red glow core (see-through)
        float knobR = radius * 0.42f;
        p.setColor(0x55FF2222);
        canvas.drawCircle(stickX, stickY, knobR * 1.15f, p);
        p.setColor(0x99111111);
        canvas.drawCircle(stickX, stickY, knobR, p);
        p.setColor(0xFFFF2222);
        canvas.drawCircle(stickX, stickY, knobR * 0.35f, p);
    }
}
