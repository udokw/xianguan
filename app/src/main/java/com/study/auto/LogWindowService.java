package com.study.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

import java.util.List;

/**
 * 悬浮实时日志窗：显示识别到的界面 + 引擎操作日志。
 * 拖动标题栏可移动，点 ⏸ 暂停/继续，点 ✕ 关闭。
 */
public class LogWindowService extends Service {

    private static final String CH = "autostudy_log";
    private static final int NOTIF_ID = 1003;

    /** 浮窗是否在显示（主界面据此更新按钮状态） */
    public static volatile boolean RUNNING = false;

    private WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private TextView tvPage, tvEvidence, tvLogs, tvFooter, tvDot, tvRun;
    private Handler ui;
    private Runnable ticker;
    private String lastPage = "";
    private String lastEvidence = "";
    private String lastLogs = "";
    private int lastCount = -1;
    private int lastOpened = -1;
    private boolean lastRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        ui = new Handler(Looper.getMainLooper());
        startForegroundNotification();
        buildWindow();
        ticker = new Runnable() {
            @Override
            public void run() {
                refresh();
                ui.postDelayed(this, 500);
            }
        };
        ui.post(ticker);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService("notification");
        nm.createNotificationChannel(new NotificationChannel(CH, "实时判别", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("闲观")
                .setContentText("实时界面判别运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildWindow() {
        wm = (WindowManager) getSystemService("window");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFFE5E6EB);
        card.setBackground(bg);
        card.setElevation(dp(6));
        root = card;

        // 标题行（可拖动）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        tvDot = new TextView(this);
        tvDot.setText("●");
        tvDot.setTextSize(11);
        tvDot.setTextColor(0xFF86909C);
        head.addView(tvDot);

        TextView title = new TextView(this);
        title.setText("闲观");
        title.setTextSize(13);
        title.setTextColor(0xFF4E5969);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(6);
        head.addView(title, tlp);

        tvRun = new TextView(this);
        tvRun.setText("⏸");
        tvRun.setTextSize(13);
        tvRun.setTextColor(0xFF4E5969);
        tvRun.setPadding(dp(8), 0, dp(8), 0);
        tvRun.setOnClickListener(v -> {
            StudyEngine eng = StudyEngine.get();
            if (eng.running)
                eng.stop();
            else
                eng.start(SelectToSpeakService.INSTANCE);
            refresh();
        });
        head.addView(tvRun);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(14);
        close.setTextColor(0xFF86909C);
        close.setPadding(dp(8), 0, dp(2), 0);
        close.setOnClickListener(v -> stopSelf());
        head.addView(close);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 结果
        tvPage = new TextView(this);
        tvPage.setTextSize(24);
        tvPage.setTextColor(0xFF86909C);
        tvPage.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(6);
        card.addView(tvPage, plp);

        // 依据
        tvEvidence = new TextView(this);
        tvEvidence.setTextSize(11);
        tvEvidence.setTextColor(0xFF86909C);
        tvEvidence.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp(4);
        card.addView(tvEvidence, elp);

        // 操作日志
        tvLogs = new TextView(this);
        tvLogs.setTextSize(9.5f);
        tvLogs.setTextColor(0xFF4E5969);
        tvLogs.setLineSpacing(dp(2), 1.0f);
        tvLogs.setMaxLines(9);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        card.addView(tvLogs, slp);

        // 页脚
        tvFooter = new TextView(this);
        tvFooter.setTextSize(10);
        tvFooter.setTextColor(0xFFA9AEB8);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(4);
        card.addView(tvFooter, flp);

        int width = dp(330);
        lp = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(150);

        // 拖动标题栏移动窗口
        head.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!moved && (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)))
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
                    default:
                        return moved; // 未拖动 → 交给 ✕ 等子控件
                }
            }
        });

        wm.addView(root, lp);
    }

    private void refresh() {
        StudyEngine eng = StudyEngine.get();
        String page = eng.page;
        String evidence = eng.evidence;
        int count = eng.nodeCount;
        int opened = eng.openedCount;
        List<String> logs = eng.logs;

        if (!page.equals(lastPage)) {
            tvPage.setText(page);
            tvPage.setTextColor(colorOf(page));
            tvDot.setTextColor(colorOf(page));
            lastPage = page;
        }
        if (!evidence.equals(lastEvidence)) {
            tvEvidence.setText(evidence);
            lastEvidence = evidence;
        }

        StringBuilder sb = new StringBuilder();
        if (logs != null && !logs.isEmpty()) {
            int from = Math.max(0, logs.size() - 9);
            for (int i = from; i < logs.size(); i++) {
                if (sb.length() > 0)
                    sb.append('\n');
                sb.append(logs.get(i));
            }
        }
        String logsText = sb.length() == 0 ? "等待日志…" : sb.toString();
        if (!logsText.equals(lastLogs)) {
            tvLogs.setText(logsText);
            lastLogs = logsText;
        }

        if (count != lastCount || opened != lastOpened) {
            tvFooter.setText("节点 " + count + " · 已打开课程 " + opened + " · 悬浮窗可拖动");
            lastCount = count;
            lastOpened = opened;
        }

        boolean run = eng.running;
        if (run != lastRunning) {
            tvRun.setText(run ? "⏸" : "▶");
            tvRun.setTextColor(run ? 0xFF4E5969 : 0xFF00B42A);
            lastRunning = run;
        }
    }

    private int colorOf(String page) {
        if (ScreenClassifier.PAGE_PANEL.equals(page))
            return 0xFF00B42A; // 绿
        if (ScreenClassifier.PAGE_DOC.equals(page))
            return 0xFFFF7D00; // 橙
        if (ScreenClassifier.PAGE_LIST.equals(page))
            return 0xFF3D7AFD; // 蓝
        return 0xFF86909C; // 灰
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RUNNING = false;
        if (ticker != null && ui != null)
            ui.removeCallbacks(ticker);
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
