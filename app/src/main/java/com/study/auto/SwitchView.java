package com.study.auto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 46dp 胶囊开关，主页的浮窗 / 悬浮球控制项用它 */
public class SwitchView extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchView v, boolean checked);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float d;
    private boolean checked;
    private float progress;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    public SwitchView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        thumb.setColor(0xFFFFFFFF);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    /** 引擎状态同步用：不触发回调、不做动画 */
    public void setCheckedImmediately(boolean v) {
        checked = v;
        progress = v ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension((int) (46 * d + 0.5f), (int) (26 * d + 0.5f));
    }

    @Override
    public boolean performClick() {
        checked = !checked;
        animateTo(checked ? 1f : 0f);
        if (listener != null)
            listener.onCheckedChanged(this, checked);
        return true;
    }

    private void animateTo(float target) {
        if (animator != null)
            animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        track.setColor(blend(0xFFE5E6EB, 0xFF3D7AFD, progress));
        oval.set(0, 0, w, h);
        c.drawRoundRect(oval, h / 2f, h / 2f, track);

        float r = h / 2f - 3 * d;
        float cx = 3 * d + r + progress * (w - 6 * d - 2 * r);
        c.drawCircle(cx, h / 2f, r, thumb);
    }

    private int blend(int from, int to, float t) {
        int a = (int) ((from >>> 24) + (((to >>> 24) - (from >>> 24)) * t));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
