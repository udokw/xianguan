package com.study.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

/**
 * 悬浮球：单击启停自动学习引擎，长按打开主界面。
 * 拖动可移动到任意位置；绿色=运行中，灰色=已停止。
 */
public class FloatingBallService extends Service {

    private static final String CH = "autostudy_ball";
    private static final int NOTIF_ID = 1004;
    public static final String ACTION_TOGGLE = "com.study.auto.TOGGLE_ENGINE";
    public static final String ACTION_START = "com.study.auto.START_ENGINE";
    public static final String ACTION_STOP = "com.study.auto.STOP_ENGINE";

    private BroadcastReceiver cmdReceiver;

    /** 悬浮球是否在显示（主界面据此更新按钮状态） */
    public static volatile boolean RUNNING = false;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private View dot;
    private TextView icon;
    private Handler ui;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            syncState();
            ui.postDelayed(this, 600);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        ui = new Handler(Looper.getMainLooper());
        startForegroundNotification();
        buildBall();
        ui.post(ticker);
        // 挂机检测线程：与 App 功能同时启用，全程监测「继续学习」「我知道了」并处理全屏缩放
        DialogWatcher.get().start(SelectToSpeakService.INSTANCE);
        cmdReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (ACTION_TOGGLE.equals(a)) {
                    toggleEngine();
                } else if (ACTION_START.equals(a)) {
                    if (MainActivity.GATE_OPEN)
                        return;
                    StudyEngine eng = StudyEngine.get();
                    if (!eng.running) {
                        SelectToSpeakService s = SelectToSpeakService.INSTANCE;
                        if (s != null)
                            eng.start(s);
                    }
                } else if (ACTION_STOP.equals(a)) {
                    StudyEngine.get().stop();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_TOGGLE);
        f.addAction(ACTION_START);
        f.addAction(ACTION_STOP);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cmdReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(cmdReceiver, f);
        }
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService("notification");
        nm.createNotificationChannel(new NotificationChannel(CH, "学习悬浮球", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("闲观")
                .setContentText("悬浮球已显示")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildBall() {
        wm = (WindowManager) getSystemService("window");

        root = new FrameLayout(this);
        root.setBackground(ballBg(0xFF86909C));

        icon = new TextView(this);
        icon.setText("▶");
        icon.setTextSize(16);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, new FrameLayout.LayoutParams(-1, -1));

        int size = dp(44);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        // 初始纵向位置按屏高夹紧，避免在矮屏/小屏设备上跑到屏幕外
        int screenH = getResources().getDisplayMetrics().heightPixels;
        lp.y = Math.min(dp(480), Math.max(dp(16), screenH - size - dp(16)));

        root.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean moved;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        downAt = System.currentTimeMillis();
                        root.setAlpha(0.65f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)))
                            moved = true;
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        root.setAlpha(1f);
                        if (!moved) {
                            long held = System.currentTimeMillis() - downAt;
                            if (held > 600)
                                openMain();
                            else
                                toggleEngine();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        root.setAlpha(1f);
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            stopSelf();
        }
    }

    private GradientDrawable ballBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), 0x66FFFFFF);
        return g;
    }

    private void toggleEngine() {
        // 全屏更新遮罩未关闭前，启停点击不执行
        if (MainActivity.GATE_OPEN)
            return;
        StudyEngine eng = StudyEngine.get();
        if (eng.running) {
            eng.stop();
            syncState();
            toast("已停止");
            return;
        }
        SelectToSpeakService s = SelectToSpeakService.INSTANCE;
        if (s == null) {
            toast("无障碍未连接，请先开启");
            openMain();
            return;
        }
        eng.start(s);
        syncState();
        toast("已开始");
    }

    private void openMain() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void syncState() {
        boolean run = StudyEngine.get().running;
        int color = run ? 0xFF00B42A : 0xFF86909C;
        root.setBackground(ballBg(color));
        icon.setText(run ? "■" : "▶");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后把球拉回屏内，避免跑到屏幕外
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            if (lp.x > w - dp(60))
                lp.x = w - dp(60);
            if (lp.y > h - dp(60))
                lp.y = h - dp(60);
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RUNNING = false;
        DialogWatcher.get().stop();
        ui.removeCallbacks(ticker);
        try {
            if (cmdReceiver != null)
                unregisterReceiver(cmdReceiver);
        } catch (Throwable ignored) {
        }
        try {
            if (root != null && wm != null)
                wm.removeView(root);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
