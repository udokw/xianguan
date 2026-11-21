package com.study.auto;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

/** 控制台 + 右侧设置抽屉。 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xFFF5F7FA;
    private static final int C_PRIMARY = 0xFF3D7AFD;
    private static final int C_T1 = 0xFF1D2129;
    private static final int C_T2 = 0xFF4E5969;
    private static final int C_T3 = 0xFF86909C;
    private static final int C_GREEN = 0xFF00B42A;
    private static final int C_ORANGE = 0xFFFF7D00;
    /** 关闭自动检测更新时，底部版本号的淡蓝色 */
    private static final int C_LIGHT_BLUE = 0xFF9CC2FF;

    private static final long PERIODIC_CHECK_MS = 5 * 60_000L;
    private static final long GATE_TIMEOUT_MS = 30_000L;
    private static final int CHECK_STARTUP = 0;
    private static final int CHECK_PERIODIC = 1;
    private static final int CHECK_MANUAL = 2;

    /**
     * 全屏更新遮罩是否打开（等待检测 / 发现新版本）。悬浮球启停点击据此被忽略，
     * 保证遮罩未关闭前不会启动引擎。
     */
    public static volatile boolean GATE_OPEN = false;

    private TextView tvHeroState, tvBadge, tvPage, tvA11y, tvA11yRight, masterText, tvVersion;
    private ImageView masterIcon;
    private View dotRun, dotMeta;
    private SwitchView swWindow, swBall;
    private LinearLayout masterBtn;
    private LinearLayout drawer;
    private View scrim;
    private Boolean lastRunShown;

    private FrameLayout gate;
    private View gateWait, gateUpdate;
    private TextView gateOldVer, gateNewVer, gateDesc, gatePrimary, gateExit;
    private String pendingUpdateVersion, pendingUpdateUrl;
    private boolean startupGateWaiting;
    /** 本次整屏提示是否由设置里的手动检测触发（是则「退出」改为「稍后再说」） */
    private boolean gateFromManual;
    private int versionTaps;
    private long lastVersionTapAt;

    private long downloadId = -1;
    private String downloadFileName;
    private BroadcastReceiver downloadReceiver;

    private final Handler ui = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 800);
        }
    };

    private final Runnable periodicCheck = new Runnable() {
        @Override
        public void run() {
            if (AppSettings.autoUpdate(MainActivity.this) && !GATE_OPEN)
                checkUpdate(CHECK_PERIODIC);
            ui.postDelayed(this, PERIODIC_CHECK_MS);
        }
    };

    /** 启动检测迟迟没有结果（断网、页面异常）时兜底放行，避免一直卡在遮罩里 */
    private final Runnable gateTimeout = () -> {
        if (!startupGateWaiting)
            return;
        startupGateWaiting = false;
        closeGate();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(C_BG);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(18));

        // 标题行 + 齿轮设置入口
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(tv("闲观学海", 27, C_T1, true), lp(0, -2, 1));

        FrameLayout btnSettings = new FrameLayout(this);
        btnSettings.setBackground(roundBg(Color.WHITE, 14, 1, 0xFFE5E6EB));
        btnSettings.setOnClickListener(v -> openDrawer());
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        btnSettings.addView(gear, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        headRow.addView(btnSettings, lp(dp(40), dp(40)));
        root.addView(headRow, lp(-1, -2));

        TextView subtitle = tv("学海无涯，闲坐自洽。", 13, C_T3, false);
        LinearLayout.LayoutParams lpSub = lp(-1, -2);
        lpSub.topMargin = dp(6);
        lpSub.bottomMargin = dp(20);
        root.addView(subtitle, lpSub);

        // 状态卡
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(20), dp(20), dp(16));
        hero.setBackground(roundBg(Color.WHITE, 24, 0, 0));

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);

        dotRun = new View(this);
        dotRun.setBackground(ovalBg(C_GREEN));
        stateRow.addView(dotRun, lp(dp(10), dp(10)));

        tvHeroState = tv("学习中", 21, C_T1, true);
        LinearLayout.LayoutParams lpState = lp(0, -2, 1);
        lpState.leftMargin = dp(10);
        stateRow.addView(tvHeroState, lpState);

        tvBadge = tv("运行中", 11, C_GREEN, false);
        tvBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvBadge.setBackground(badgeBg(true));
        stateRow.addView(tvBadge, lp(-2, -2));
        hero.addView(stateRow, lp(-1, -2));

        tvPage = tv("当前：待启动\n播放 / 文档 / 列表，全流程托管", 13, C_T2, false);
        tvPage.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams lpPage = lp(-1, -2);
        lpPage.leftMargin = dp(20);
        lpPage.topMargin = dp(10);
        hero.addView(tvPage, lpPage);

        View heroLine = new View(this);
        heroLine.setBackgroundColor(0xFFF0F1F4);
        LinearLayout.LayoutParams lpLine = lp(-1, dp(1));
        lpLine.topMargin = dp(16);
        hero.addView(heroLine, lpLine);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpMeta = lp(-1, -2);
        lpMeta.topMargin = dp(14);
        dotMeta = new View(this);
        dotMeta.setBackground(ovalBg(C_GREEN));
        metaRow.addView(dotMeta, lp(dp(6), dp(6)));
        tvA11y = tv("无障碍服务", 12, C_T3, false);
        LinearLayout.LayoutParams lpA11y = lp(0, -2, 1);
        lpA11y.leftMargin = dp(8);
        metaRow.addView(tvA11y, lpA11y);
        tvA11yRight = tv("已连接", 12, C_T2, true);
        metaRow.addView(tvA11yRight, lp(-2, -2));
        metaRow.setOnClickListener(v -> {
            if (SelectToSpeakService.INSTANCE == null)
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        hero.addView(metaRow, lpMeta);
        root.addView(hero, lp(-1, -2));

        TextView section = tv("控制", 12, C_T3, false);
        section.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lpSec = lp(-1, -2);
        lpSec.topMargin = dp(22);
        lpSec.bottomMargin = dp(10);
        lpSec.leftMargin = dp(2);
        root.addView(section, lpSec);

        // 控制面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBg(Color.WHITE, 24, 0, 0));

        swWindow = new SwitchView(this);
        swWindow.setCheckedImmediately(LogWindowService.RUNNING);
        swWindow.setOnCheckedChangeListener((v, checked) -> {
            if (checked != LogWindowService.RUNNING)
                toggleWindow();
        });
        panel.addView(ctrlRow(R.drawable.ic_panel, "实时判别浮窗", "显示识别结果与实时日志", swWindow));

        View rowLine = new View(this);
        rowLine.setBackgroundColor(0xFFF1F2F5);
        panel.addView(rowLine, lp(-1, dp(1)));

        swBall = new SwitchView(this);
        swBall.setCheckedImmediately(FloatingBallService.RUNNING);
        swBall.setOnCheckedChangeListener((v, checked) -> {
            if (checked != FloatingBallService.RUNNING)
                toggleBall();
        });
        panel.addView(ctrlRow(R.drawable.ic_ball, "悬浮球", "单击启停 · 长按打开本页", swBall));
        root.addView(panel, lp(-1, -2));

        // 主控按钮
        masterBtn = new LinearLayout(this);
        masterBtn.setOrientation(LinearLayout.HORIZONTAL);
        masterBtn.setGravity(Gravity.CENTER);
        masterBtn.setClickable(true);
        masterIcon = new ImageView(this);
        masterBtn.addView(masterIcon, lp(dp(15), dp(15)));
        masterText = tv("开始学习", 15, C_T2, true);
        LinearLayout.LayoutParams lpMt = lp(-2, -2);
        lpMt.leftMargin = dp(9);
        masterBtn.addView(masterText, lpMt);
        masterBtn.setOnClickListener(v -> toggleMaster());
        LinearLayout.LayoutParams lpMaster = lp(-1, dp(50));
        lpMaster.topMargin = dp(16);
        root.addView(masterBtn, lpMaster);
        updateMaster(StudyEngine.get().running);

        root.addView(new View(this), lp(-1, 0, 1));

        // 底部版本号：连点 7 次切换自动检测更新（无任何提示）
        tvVersion = tv("v" + appVersionName(), 11, C_T3, false);
        tvVersion.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpVer = lp(-1, -2);
        lpVer.topMargin = dp(18);
        root.addView(tvVersion, lpVer);

        sv.addView(root);
        shell.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        // 抽屉遮罩
        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        shell.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        drawer = buildDrawer();
        FrameLayout.LayoutParams lpD = new FrameLayout.LayoutParams(dp(300), -1);
        lpD.gravity = Gravity.END;
        shell.addView(drawer, lpD);

        buildGate(shell);

        setContentView(shell);

        // 打开应用即自动显示悬浮球（日志浮窗默认关闭，需要时手动开）
        if (Settings.canDrawOverlays(this) && !FloatingBallService.RUNNING) {
            startService(new Intent(this, FloatingBallService.class));
        }

        tvVersion.setOnClickListener(v -> onVersionTap());
        applyAutoUpdateUi();

        // 启动即强制检测：整屏遮罩挡住界面，检测完才放行主界面
        if (AppSettings.autoUpdate(this)) {
            showGateWaiting();
            startupGateWaiting = true;
            ui.postDelayed(() -> checkUpdate(CHECK_STARTUP), 300);
            ui.postDelayed(gateTimeout, GATE_TIMEOUT_MS);
        }

        // 运行期间每 5 分钟检测一次，发现新版本先暂停学习再整屏提示
        ui.postDelayed(periodicCheck, PERIODIC_CHECK_MS);
    }

    /** 读取安装包的 versionName，随 build.gradle 自动更新，不写死 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ==================== 设置抽屉 ====================

    private LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(Color.WHITE);
        d.setPadding(dp(20), dp(26), dp(20), dp(18));
        d.setTranslationX(dp(320));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("设置", 20, C_T1, true), lp(0, -2, 1));

        FrameLayout close = new FrameLayout(this);
        close.setBackground(roundBg(0xFFF5F7FA, 999, 0, 0));
        close.setOnClickListener(v -> closeDrawer());
        ImageView x = new ImageView(this);
        x.setImageResource(R.drawable.ic_close);
        close.addView(x, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        head.addView(close, lp(dp(34), dp(34)));
        LinearLayout.LayoutParams lph = lp(-1, -2);
        lph.bottomMargin = dp(22);
        d.addView(head, lph);

        TextView label = tv("学习时长", 12, C_T3, false);
        label.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lpl = lp(-1, -2);
        lpl.bottomMargin = dp(10);
        d.addView(label, lpl);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(0xFFF7F8FA, 18, 0, 0));
        card.setPadding(dp(16), 0, dp(16), 0);
        card.addView(durationRow("视频兜底", AppSettings.playMin(this),
                v -> AppSettings.setPlayMin(this, v)));
        View line = new View(this);
        line.setBackgroundColor(0xFFECEEF2);
        card.addView(line, lp(-1, dp(1)));
        card.addView(durationRow("文档学习", AppSettings.docMin(this),
                v -> AppSettings.setDocMin(this, v)));
        d.addView(card, lp(-1, -2));

        TextView delayLabel = tv("返回列表", 12, C_T3, false);
        delayLabel.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lpDelay = lp(-1, -2);
        lpDelay.topMargin = dp(20);
        lpDelay.bottomMargin = dp(10);
        d.addView(delayLabel, lpDelay);

        LinearLayout delayCard = new LinearLayout(this);
        delayCard.setOrientation(LinearLayout.VERTICAL);
        delayCard.setBackground(roundBg(0xFFF7F8FA, 18, 0, 0));
        delayCard.setPadding(dp(16), 0, dp(16), 0);
        delayCard.addView(delayRow());
        d.addView(delayCard, lp(-1, -2));

        TextView note = tv("时长输入后自动保存；返回延时滑动即时生效。", 11, C_T3, false);
        note.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams lpn = lp(-1, -2);
        lpn.topMargin = dp(14);
        d.addView(note, lpn);

        // 版本更新：常驻入口，手动检测一次；关闭自动检测后也随时能主动查
        TextView upLabel = tv("版本更新", 12, C_T3, false);
        upLabel.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lpUp = lp(-1, -2);
        lpUp.topMargin = dp(20);
        lpUp.bottomMargin = dp(10);
        d.addView(upLabel, lpUp);

        LinearLayout upCard = new LinearLayout(this);
        upCard.setOrientation(LinearLayout.HORIZONTAL);
        upCard.setGravity(Gravity.CENTER_VERTICAL);
        upCard.setBackground(roundBg(0xFFF7F8FA, 18, 0, 0));
        upCard.setPadding(dp(16), dp(13), dp(12), dp(13));

        LinearLayout upText = new LinearLayout(this);
        upText.setOrientation(LinearLayout.VERTICAL);
        upText.addView(tv("检查新版本", 14, C_T1, true), lp(-1, -2));
        TextView upCur = tv("当前 v" + appVersionName(), 11, C_T3, false);
        LinearLayout.LayoutParams lpCur = lp(-1, -2);
        lpCur.topMargin = dp(3);
        upText.addView(upCur, lpCur);
        upCard.addView(upText, lp(0, -2, 1));

        TextView upBtn = tv("版本更新", 13, Color.WHITE, true);
        upBtn.setGravity(Gravity.CENTER);
        upBtn.setBackground(roundBg(C_PRIMARY, 14, 0, 0));
        upBtn.setPadding(dp(16), dp(9), dp(16), dp(9));
        upBtn.setOnClickListener(v -> startManualUpdateCheck());
        upCard.addView(upBtn, lp(-2, -2));

        d.addView(upCard, lp(-1, -2));

        d.addView(new View(this), lp(-1, 0, 1));
        d.addView(tv("v" + appVersionName(), 11, C_T3, false), lp(-1, -2));
        return d;
    }

    // ==================== 更新闸门（启动强制 / 每 5 分钟） ====================

    /** 连点 7 次版本号切换自动检测更新，不弹任何提示；开关状态写入本地，重启沿用 */
    private void onVersionTap() {
        long now = System.currentTimeMillis();
        if (now - lastVersionTapAt > 3000)
            versionTaps = 0;   // 断档太久重新计数，避免日常误触积累
        lastVersionTapAt = now;
        if (++versionTaps < 7)
            return;
        versionTaps = 0;
        AppSettings.setAutoUpdate(this, !AppSettings.autoUpdate(this));
        applyAutoUpdateUi();
    }

    /** 开启=与原来一致的灰黑色版本号；关闭=淡蓝色，只有知道这个暗门的人才看得出来 */
    private void applyAutoUpdateUi() {
        if (tvVersion != null)
            tvVersion.setTextColor(AppSettings.autoUpdate(this) ? C_T3 : C_LIGHT_BLUE);
    }

    /** 整屏更新遮罩：等待检测中 / 发现新版本两种状态，遮住并吃掉所有触摸 */
    private void buildGate(FrameLayout shell) {
        gate = new FrameLayout(this);
        gate.setBackgroundColor(C_BG);
        gate.setClickable(true);
        gate.setFocusable(true);
        gate.setVisibility(View.GONE);

        LinearLayout wait = new LinearLayout(this);
        wait.setOrientation(LinearLayout.VERTICAL);
        wait.setGravity(Gravity.CENTER);
        wait.setPadding(dp(24), dp(24), dp(24), dp(24));

        ProgressBar spin = new ProgressBar(this);
        spin.getIndeterminateDrawable().setColorFilter(C_PRIMARY, PorterDuff.Mode.SRC_IN);
        wait.addView(spin, lp(dp(44), dp(44)));

        TextView waitTitle = tv("正在检查更新", 16, C_T1, true);
        LinearLayout.LayoutParams lpw = lp(-2, -2);
        lpw.topMargin = dp(18);
        lpw.gravity = Gravity.CENTER_HORIZONTAL;
        wait.addView(waitTitle, lpw);

        TextView waitSub = tv("确认版本后自动进入主界面", 12, C_T3, false);
        LinearLayout.LayoutParams lpw2 = lp(-2, -2);
        lpw2.topMargin = dp(8);
        lpw2.gravity = Gravity.CENTER_HORIZONTAL;
        wait.addView(waitSub, lpw2);

        gateWait = wait;
        gate.addView(wait, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout upd = new LinearLayout(this);
        upd.setOrientation(LinearLayout.VERTICAL);
        upd.setPadding(dp(24), dp(46), dp(24), dp(24));
        upd.setVisibility(View.GONE);

        upd.addView(tv("发现新版本", 25, C_T1, true), lp(-1, -2));

        TextView updSub = tv("保持最新版本，才能继续稳定使用。", 13, C_T3, false);
        LinearLayout.LayoutParams lpus = lp(-1, -2);
        lpus.topMargin = dp(8);
        upd.addView(updSub, lpus);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(Color.WHITE, 24, 0, 0));
        card.setPadding(dp(22), dp(22), dp(22), dp(20));

        LinearLayout verRow = new LinearLayout(this);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER_VERTICAL);
        gateOldVer = tv("v" + appVersionName(), 13, C_T3, false);
        verRow.addView(gateOldVer, lp(-2, -2));
        verRow.addView(tv("  →  ", 13, C_T3, false), lp(-2, -2));
        gateNewVer = tv("v?", 13, C_PRIMARY, true);
        verRow.addView(gateNewVer, lp(-2, -2));
        card.addView(verRow, lp(-1, -2));

        gateDesc = tv("", 12, C_T2, false);
        gateDesc.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams lpd = lp(-1, -2);
        lpd.topMargin = dp(14);
        card.addView(gateDesc, lpd);

        LinearLayout.LayoutParams lpCard = lp(-1, -2);
        lpCard.topMargin = dp(22);
        upd.addView(card, lpCard);

        upd.addView(new View(this), lp(-1, 0, 1));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView exit = tv("退出", 14, C_T2, true);
        exit.setGravity(Gravity.CENTER);
        exit.setBackground(roundBg(Color.WHITE, 25, 1, 0xFFE5E6EB));
        exit.setOnClickListener(v -> {
            if (gateFromManual) {
                // 手动检测触发的提示不能把 App 关掉，只关提示回设置
                gateFromManual = false;
                closeGate();
            } else {
                exitApp();
            }
        });
        gateExit = exit;
        LinearLayout.LayoutParams lpL = lp(0, dp(50), 1);
        lpL.rightMargin = dp(10);
        btns.addView(exit, lpL);

        gatePrimary = tv("立即更新", 14, Color.WHITE, true);
        gatePrimary.setGravity(Gravity.CENTER);
        gatePrimary.setBackground(roundBg(C_PRIMARY, 25, 0, 0));
        gatePrimary.setOnClickListener(v -> {
            String url = pendingUpdateUrl;
            String ver = pendingUpdateVersion;
            closeGate();
            if (url != null)
                startApkDownload(url, ver);
            else
                openSharePage();
        });
        btns.addView(gatePrimary, lp(0, dp(50), 1));
        upd.addView(btns, lp(-1, -2));

        gateUpdate = upd;
        gate.addView(upd, new FrameLayout.LayoutParams(-1, -1));

        shell.addView(gate, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openGate() {
        GATE_OPEN = true;
        if (gate != null)
            gate.setVisibility(View.VISIBLE);
    }

    private void closeGate() {
        GATE_OPEN = false;
        if (gate != null)
            gate.setVisibility(View.GONE);
    }

    /** 更新页上的「退出」：停掉学习与前台服务，直接关闭 App */
    private void exitApp() {
        closeGate();
        StudyEngine eng = StudyEngine.get();
        if (eng.running)
            eng.stop();
        try {
            stopService(new Intent(this, FloatingBallService.class));
            stopService(new Intent(this, LogWindowService.class));
        } catch (Throwable ignored) {
        }
        finishAffinity();
    }

    private void showGateWaiting() {
        pendingUpdateVersion = null;
        pendingUpdateUrl = null;
        gateWait.setVisibility(View.VISIBLE);
        gateUpdate.setVisibility(View.GONE);
        openGate();
    }

    private void showGateUpdate(String latest, String downloadUrl, String error) {
        pendingUpdateVersion = latest;
        pendingUpdateUrl = downloadUrl;
        gateOldVer.setText("v" + appVersionName());
        gateNewVer.setText("v" + latest);
        gateDesc.setText(downloadUrl != null
                ? "点「立即更新」下载到系统「下载」目录，下载完成会自动调起系统安装器。"
                : "没取到下载直链，点「去下载」在浏览器打开更新页。"
                + (error == null ? "" : "\n" + error));
        gatePrimary.setText(downloadUrl != null ? "立即更新" : "去下载");
        gateExit.setText(gateFromManual ? "稍后再说" : "退出");
        gateWait.setVisibility(View.GONE);
        gateUpdate.setVisibility(View.VISIBLE);
        openGate();
    }

    /**
     * @param mode CHECK_STARTUP：启动强制检测，没发现更新（含检测失败）就放行主界面；
     *             CHECK_PERIODIC：运行中每 5 分钟检测，发现更新先暂停学习再整屏提示；
     *             CHECK_MANUAL：设置里手动点「版本更新」，没有新版本时给一句结果提示。
     */
    private void checkUpdate(int mode) {
        if (mode == CHECK_STARTUP && !AppSettings.autoUpdate(this)) {
            startupGateWaiting = false;
            closeGate();
            return;
        }
        boolean started = UpdateChecker.check(this, (latest, downloadUrl, error) -> {
            startupGateWaiting = false;
            boolean hasNew = latest != null
                    && UpdateChecker.compare(latest, appVersionName()) > 0;
            if (mode == CHECK_STARTUP) {
                if (hasNew)
                    showGateUpdate(latest, downloadUrl, error);
                else
                    closeGate();
                return;
            }
            if (mode == CHECK_MANUAL) {
                if (hasNew) {
                    pauseForUpdate();
                    showGateUpdate(latest, downloadUrl, error);
                } else {
                    closeGate();
                    Toast.makeText(this, error != null ? error
                                    : "已是最新版本 v" + appVersionName(),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (hasNew) {
                pauseForUpdate();
                showGateUpdate(latest, downloadUrl, error);
            }
        });
        if (!started) {
            if (mode == CHECK_STARTUP) {
                // 已有检测在跑（例如重复触发）：不让遮罩一直挂着
                startupGateWaiting = false;
                closeGate();
            } else if (mode == CHECK_MANUAL) {
                startupGateWaiting = false;
                closeGate();
                Toast.makeText(this, "正在检测中，请稍候", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 设置里的「版本更新」：关掉抽屉，复用手动检测流程（有新版走同一个整屏提示） */
    private void startManualUpdateCheck() {
        closeDrawer();
        gateFromManual = true;
        showGateWaiting();
        startupGateWaiting = true;
        ui.postDelayed(() -> checkUpdate(CHECK_MANUAL), 230);
        ui.postDelayed(gateTimeout, GATE_TIMEOUT_MS);
    }

    /** 检测到新版本：先暂停学习，再整屏提示，避免旧版本继续跑 */
    private void pauseForUpdate() {
        StudyEngine eng = StudyEngine.get();
        if (eng.running) {
            eng.stop();
            lastRunShown = false;
            updateMaster(false);
            ui.postDelayed(this::refresh, 400);
        }
    }

    // ==================== 应用内下载 + 调起安装器 ====================

    /** 用系统 DownloadManager 下载到公共「下载」目录，完成后自动调起安装器 */
    private void startApkDownload(String url, String version) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许「安装未知应用」，再回来点一次立即更新",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
            return;
        }
        downloadFileName = "闲观-v" + version + ".apk";

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(this, "下载服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setMimeType("application/vnd.android.package-archive");
        req.addRequestHeader("User-Agent", UpdateChecker.userAgent());
        req.setTitle("闲观 v" + version);
        req.setDescription("正在下载更新包…");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        // 落到系统公共下载目录，用户可在文件管理器里直接找到
        req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,
                downloadFileName);
        try {
            downloadId = dm.enqueue(req);
        } catch (Throwable t) {
            Toast.makeText(this, "下载启动失败：" + t.getClass().getSimpleName()
                    + " " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        registerDownloadReceiver();
        Toast.makeText(this, "已开始下载 v" + version + "，完成后会自动弹出安装器；安装包在系统「下载」目录",
                Toast.LENGTH_LONG).show();
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null)
            return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId)
                    installDownloadedApk();
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
            else
                registerReceiver(downloadReceiver, filter);
        } catch (Throwable ignored) {
        }
    }

    private void installDownloadedApk() {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(this, "下载服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        int status = -1, reason = -1;
        android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
        if (c != null) {
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            }
            c.close();
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, "安装包下载失败（状态 " + status + "，原因码 " + reason
                    + "），可点「去下载」用浏览器下载", Toast.LENGTH_LONG).show();
            return;
        }
        // 公共下载目录只能用 DownloadManager 给出的 content:// 交给安装器
        Uri apkUri = dm.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            Toast.makeText(this, "安装包已下载但无法获取地址，请到系统「下载」目录手动安装 "
                    + downloadFileName, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apkUri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法调起安装器：" + t.getClass().getSimpleName()
                    + "。安装包在系统「下载」目录：" + downloadFileName, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GATE_OPEN = false;
        ui.removeCallbacksAndMessages(null);
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Throwable ignored) {
            }
            downloadReceiver = null;
        }
    }

    /** 用浏览器打开飞机盘更新页；没有浏览器时把链接复制到剪贴板 */
    private void openSharePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.SHARE_URL)));
        } catch (Throwable t) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(
                    CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "更新链接", UpdateChecker.SHARE_URL));
                Toast.makeText(this, "没有浏览器，更新链接已复制", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, UpdateChecker.SHARE_URL, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 一行「标签 + 数字输入框」，输入合法的 1～60 分钟后立即写入 SharedPreferences。 */
    private LinearLayout durationRow(String label, int minutes, java.util.function.IntConsumer save) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(tv(label, 14, C_T1, true), lp(0, -2, 1));

        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.HORIZONTAL);
        editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setBackground(roundBg(Color.WHITE, 12, 1, 0xFFE5E6EB));
        editor.setPadding(dp(4), dp(2), dp(4), dp(2));

        final int[] current = { Math.max(1, Math.min(60, minutes)) };
        EditText input = new EditText(this);
        input.setText(String.valueOf(current[0]));
        input.setTextSize(14);
        input.setTextColor(C_T1);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(2) });
        input.setPadding(0, 0, 0, 0);
        input.setBackground(null);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Integer value = validMinutes(s.toString());
                if (value != null) {
                    current[0] = value;
                    save.accept(value);
                }
            }
        });
        // 清空或越界时不覆盖原配置；输入结束离开输入框后恢复到最后一个有效值。
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus)
                return;
            Integer value = validMinutes(input.getText().toString());
            int shown = value != null ? value : current[0];
            if (!String.valueOf(shown).contentEquals(input.getText())) {
                input.setText(String.valueOf(shown));
                input.setSelection(input.length());
            }
        });
        editor.addView(input, lp(dp(44), dp(36)));

        TextView unit = tv("分钟", 11, C_T3, false);
        LinearLayout.LayoutParams lpUnit = lp(-2, -2);
        lpUnit.leftMargin = dp(3);
        lpUnit.rightMargin = dp(5);
        editor.addView(unit, lpUnit);
        row.addView(editor, lp(-2, -2));
        return row;
    }

    private Integer validMinutes(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 1 && value <= 60 ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 返回列表延时：1～9 秒，拖动过程中实时显示并保存，引擎下一次等待立即读取新值。 */
    private LinearLayout delayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("刷新等待", 14, C_T1, true), lp(0, -2, 1));
        TextView value = tv(AppSettings.backToListSec(this) + " 秒", 13, C_PRIMARY, true);
        head.addView(value, lp(-2, -2));
        row.addView(head, lp(-1, -2));

        SeekBar seek = new SeekBar(this);
        seek.setMax(8);
        seek.setProgress(AppSettings.backToListSec(this) - 1);
        seek.getProgressDrawable().setColorFilter(C_PRIMARY, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(C_PRIMARY, PorterDuff.Mode.SRC_IN);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int sec = progress + 1;
                value.setText(sec + " 秒");
                AppSettings.setBackToListSec(MainActivity.this, sec);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(seek, lp(-1, dp(34)));

        LinearLayout range = new LinearLayout(this);
        range.setOrientation(LinearLayout.HORIZONTAL);
        range.addView(tv("1 秒", 10, C_T3, false), lp(0, -2, 1));
        TextView max = tv("9 秒", 10, C_T3, false);
        max.setGravity(Gravity.END);
        range.addView(max, lp(-2, -2));
        row.addView(range, lp(-1, -2));
        return row;
    }

    private void openDrawer() {
        drawer.animate().translationX(0).setDuration(220).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
    }

    private void closeDrawer() {
        drawer.animate().translationX(dp(320)).setDuration(200).start();
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    // ==================== 浮窗开关 ====================

    private void toggleWindow() {
        if (LogWindowService.RUNNING) {
            stopService(new Intent(this, LogWindowService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            startService(new Intent(this, LogWindowService.class));
        }
        ui.postDelayed(this::refresh, 300);
    }

    private void toggleBall() {
        if (FloatingBallService.RUNNING) {
            stopService(new Intent(this, FloatingBallService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            startService(new Intent(this, FloatingBallService.class));
        }
        ui.postDelayed(this::refresh, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private void refresh() {
        boolean a11y = SelectToSpeakService.INSTANCE != null;
        dotMeta.setBackground(ovalBg(a11y ? C_GREEN : C_ORANGE));
        tvA11yRight.setText(a11y ? "已连接" : "去开启");
        tvA11yRight.setTextColor(a11y ? C_T2 : C_ORANGE);

        StudyEngine eng = StudyEngine.get();
        boolean run = eng.running;
        dotRun.setBackground(ovalBg(run ? C_GREEN : 0xFFB7BCC4));
        tvHeroState.setText(run ? "学习中" : "待机");
        tvBadge.setText(run ? "运行中" : "已暂停");
        tvBadge.setTextColor(run ? C_GREEN : C_T3);
        tvBadge.setBackground(badgeBg(run));
        tvPage.setText("当前：" + (run ? eng.page : "待启动") + "\n播放 / 文档 / 列表，全流程托管");

        swWindow.setCheckedImmediately(LogWindowService.RUNNING);
        swBall.setCheckedImmediately(FloatingBallService.RUNNING);

        if (lastRunShown == null || lastRunShown != run) {
            lastRunShown = run;
            updateMaster(run);
        }
    }

    /** 主控按钮：运行中显示「暂停学习」，停止时显示「开始学习」 */
    private void updateMaster(boolean running) {
        if (masterBtn == null)
            return;
        masterText.setText(running ? "暂停学习" : "开始学习");
        masterIcon.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        masterText.setTextColor(running ? Color.WHITE : C_T2);
        masterIcon.setColorFilter(running ? Color.WHITE : C_T2);
        masterBtn.setBackground(running
                ? roundBg(C_PRIMARY, 25, 0, 0)
                : roundBg(Color.WHITE, 25, 1, 0xFFE5E6EB));
    }

    private void toggleMaster() {
        StudyEngine eng = StudyEngine.get();
        if (eng.running) {
            eng.stop();
            lastRunShown = false;
            updateMaster(false);
        } else {
            SelectToSpeakService s = SelectToSpeakService.INSTANCE;
            if (s == null) {
                Toast.makeText(this, "无障碍未连接，请先开启", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            eng.start(s);
            lastRunShown = true;
            updateMaster(true);
        }
        ui.postDelayed(this::refresh, 400);
    }

    // ==================== 样式工具 ====================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    private TextView tv(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold)
            t.getPaint().setFakeBoldText(true);
        return t;
    }

    /** 控制面板的一行：图标 + 标题/说明 + 开关 */
    private LinearLayout ctrlRow(int iconRes, String title, String sub, SwitchView sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(roundBg(0xFFEAF1FF, 14, 0, 0));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        row.addView(iconBox, lp(dp(42), dp(42)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 15, C_T1, true), lp(-1, -2));
        LinearLayout.LayoutParams lpS = lp(-1, -2);
        lpS.topMargin = dp(3);
        texts.addView(tv(sub, 12, C_T3, false), lpS);
        LinearLayout.LayoutParams lpt = lp(0, -2, 1);
        lpt.leftMargin = dp(14);
        row.addView(texts, lpt);

        row.addView(sw, lp(dp(46), dp(26)));
        return row;
    }

    private GradientDrawable roundBg(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0)
            g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private GradientDrawable ovalBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable badgeBg(boolean running) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(running ? 0x0F00B42A : 0xFFEEF0F3);
        g.setCornerRadius(dp(999));
        return g;
    }
}
