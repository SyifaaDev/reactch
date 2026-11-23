package com.carrace;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Animasi tikus berlari (outline biru) untuk layar loading. */
public class MouseLoadingView extends View {

    // Ruang gambar logis; skala otomatis mengikuti ukuran view.
    private static final float UNIT_W = 260f;
    private static final float UNIT_H = 180f;
    private static final float GROUND_Y = 150f;
    private static final int COLOR_MOUSE = 0xFF1565C0;
    private static final int COLOR_GROUND = 0xFFB0BEC5;

    private final Paint mousePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private float phase; // 0..1, satu siklus lari

    public MouseLoadingView(Context context) {
        super(context);

        mousePaint.setStyle(Paint.Style.STROKE);
        mousePaint.setStrokeWidth(5f);
        mousePaint.setStrokeCap(Paint.Cap.ROUND);
        mousePaint.setStrokeJoin(Paint.Join.ROUND);
        mousePaint.setColor(COLOR_MOUSE);

        groundPaint.setStyle(Paint.Style.STROKE);
        groundPaint.setStrokeWidth(3f);
        groundPaint.setStrokeCap(Paint.Cap.ROUND);
        groundPaint.setColor(COLOR_GROUND);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(COLOR_MOUSE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(650L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!animator.isRunning()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = Math.min(getWidth() / UNIT_W, getHeight() / UNIT_H);
        if (s <= 0f) {
            return;
        }
        canvas.save();
        canvas.translate((getWidth() - UNIT_W * s) / 2f, (getHeight() - UNIT_H * s) / 2f);
        canvas.scale(s, s);
        drawGround(canvas);
        drawMouse(canvas);
        canvas.restore();
    }

    private void drawGround(Canvas canvas) {
        canvas.drawLine(0f, GROUND_Y, UNIT_W, GROUND_Y, groundPaint);
        // Garis putus-putus bergerak ke kiri supaya terlihat tikus sedang berlari ke kanan.
        float offset = phase * 40f;
        for (int i = -1; i <= 7; i++) {
            float x = i * 40f - offset;
            canvas.drawLine(x, GROUND_Y + 12f, x + 20f, GROUND_Y + 12f, groundPaint);
        }
    }

    private void drawMouse(Canvas canvas) {
        double theta = phase * 2.0 * Math.PI;
        float bob = (float) Math.abs(Math.sin(theta)) * 4f;

        canvas.save();
        canvas.translate(10f, 8f - bob);

        // Kaki: dua pasang, berlawanan fase supaya terlihat berlari.
        drawLeg(canvas, 150f, 98f, theta);
        drawLeg(canvas, 140f, 98f, theta + Math.PI);
        drawLeg(canvas, 98f, 98f, theta + Math.PI);
        drawLeg(canvas, 88f, 98f, theta);

        // Badan dan kepala (outline saja).
        rect.set(70f, 50f, 170f, 100f);
        canvas.drawOval(rect, mousePaint);
        rect.set(30f, 56f, 86f, 96f);
        canvas.drawOval(rect, mousePaint);

        // Telinga, mata, hidung.
        canvas.drawCircle(62f, 52f, 12f, mousePaint);
        canvas.drawCircle(48f, 72f, 3.5f, dotPaint);
        canvas.drawCircle(30f, 80f, 4.5f, dotPaint);

        // Ekor.
        path.reset();
        path.moveTo(168f, 74f);
        path.cubicTo(198f, 80f, 218f, 64f, 210f, 38f);
        canvas.drawPath(path, mousePaint);

        canvas.restore();
    }

    private void drawLeg(Canvas canvas, float hipX, float hipY, double t) {
        float a1 = (float) (0.6 * Math.sin(t));
        float kneeX = hipX + 22f * (float) Math.sin(a1);
        float kneeY = hipY + 22f * (float) Math.cos(a1);
        float a2 = a1 * 0.5f + 0.35f;
        float footX = kneeX + 20f * (float) Math.sin(a2);
        float footY = kneeY + 20f * (float) Math.cos(a2);

        path.reset();
        path.moveTo(hipX, hipY);
        path.lineTo(kneeX, kneeY);
        path.lineTo(footX, footY);
        canvas.drawPath(path, mousePaint);
    }
}
