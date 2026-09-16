package org.libsdl.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Floating ember/spark particles rising from the bottom, Yagami flame vibe.
 * Lightweight: ~40 particles, invalidates at ~30fps only while visible.
 */
public class EmberView extends View {

    private static class Ember {
        float x, y, size, speed, drift, alpha, maxAlpha;
        int color;
    }

    private final List<Ember> embers = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private boolean running = false;
    private long lastFrame = 0;

    // Fiery palette: deep red -> orange -> bright yellow-white
    private static final int[] COLORS = {
        0xFFFF2222, 0xFFFF4400, 0xFFFF6600, 0xFFFF8833, 0xFFFFAA44, 0xFFFFDD88
    };

    public EmberView(Context context) {
        super(context);
        init();
    }

    public EmberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        embers.clear();
        // Spawn embers across the width, biased to bottom half
        for (int i = 0; i < 45; i++) {
            embers.add(newEmber(w, h, true));
        }
    }

    private Ember newEmber(int w, int h, boolean randomY) {
        Ember e = new Ember();
        e.x = random.nextFloat() * w;
        e.y = randomY ? random.nextFloat() * h : h + 20 + random.nextFloat() * 60;
        e.size = 2 + random.nextFloat() * 7;
        e.speed = 40 + random.nextFloat() * 130;      // px per second upward
        e.drift = (random.nextFloat() - 0.5f) * 60;   // horizontal sway
        e.maxAlpha = 0.35f + random.nextFloat() * 0.55f;
        e.alpha = 0;
        e.color = COLORS[random.nextInt(COLORS.length)];
        return e;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrame = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running || getWidth() == 0) return;

        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastFrame) / 1000f, 0.1f);
        lastFrame = now;

        for (Ember e : embers) {
            // Rise + sway
            e.y -= e.speed * dt;
            e.x += (float) (Math.sin(now / 700.0 + e.y / 90.0) * e.drift * dt);

            // Fade in at bottom, fade out at top
            float h = getHeight();
            float lifePhase = 1f - (e.y / (h + 80)); // 0 bottom -> 1 top
            if (lifePhase < 0.15f) {
                e.alpha = e.maxAlpha * (lifePhase / 0.15f);
            } else if (lifePhase > 0.75f) {
                e.alpha = e.maxAlpha * Math.max(0, (1f - lifePhase) / 0.25f);
            } else {
                e.alpha = e.maxAlpha;
            }

            // Shrink slightly as it rises
            float s = e.size * (0.5f + 0.5f * (e.y / (h + 80)));

            // Outer glow
            paint.setColor(e.color);
            paint.setAlpha((int) (e.alpha * 90));
            canvas.drawCircle(e.x, e.y, s * 2.6f, paint);
            // Core
            paint.setAlpha((int) (e.alpha * 255));
            canvas.drawCircle(e.x, e.y, s, paint);
            // Hot center
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (e.alpha * 160));
            canvas.drawCircle(e.x, e.y, s * 0.35f, paint);

            // Respawn at bottom when off-screen
            if (e.y < -30) {
                Ember n = newEmber(getWidth(), h, false);
                e.x = n.x; e.y = n.y; e.size = n.size;
                e.speed = n.speed; e.drift = n.drift;
                e.maxAlpha = n.maxAlpha; e.color = n.color;
                e.alpha = 0;
            }
        }

        // ~30fps
        postInvalidateDelayed(33);
    }
}
