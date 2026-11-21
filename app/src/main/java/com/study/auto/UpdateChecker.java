package com.study.auto;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 更新检测：直接调用飞机盘分享接口（不依赖 WebView）。
 * <p>
 * 流程：列分享目录（根 → 应用文件夹 → 安卓）→ 取版本号最大的 apk → 请求 redirect 接口
 * 只读 302 的 Location 拿到带签名的直链。直链会过期，每次现取，绝不缓存。
 * 失败时把 HTTP 码、内容类型、实际地址写进错误文案，便于真机取证。
 */
public final class UpdateChecker {

    /** 更新包所在的飞机盘分享链接（固定不变，新版本直接传到同一个文件夹） */
    public static final String SHARE_ID = "9dyOULa5";
    public static final String SHARE_URL = "https://share.feijipan.com/s/" + SHARE_ID;

    private static final String API = "https://api.feijipan.com";
    /** 分享页前端 encryptHex 用的固定 key */
    private static final String AES_KEY = "dingHao-disk-app";
    private static final String TAG = "UPDATE";

    /** 分享根目录里的应用文件夹名（contains 匹配，大小写不敏感） */
    private static final String[] APP_DIRS = {"闲观", "GTStudy", "自动学习"};
    /** 安卓平台子目录名 */
    private static final String[] ANDROID_DIRS = {"安卓", "android"};

    /** 文件名约定：闲观-v1.0.48.apk（也容忍 (1) 后缀、空格、全角破折号） */
    private static final Pattern APK_VERSION = Pattern.compile(
            "闲观[-_－— ]*[vV]?([0-9]+(?:\\.[0-9]+){1,3})\\s*(\\(\\d+\\))?\\.apk");

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static volatile boolean running;

    /**
     * latest 为 null 表示没读到版本；downloadUrl 为 null 表示没拿到直链
     * （此时回落到浏览器打开分享页）；error 是可直接展示的失败原因。
     */
    public interface Listener {
        void onChecked(String latest, String downloadUrl, String error);
    }

    private UpdateChecker() {
    }

    /** 当前安装包的 versionName，例：1.0.48 */
    public static String currentVersion(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 版本号比较：返回 >0 表示 a 比 b 新 */
    public static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int vi = i < x.length ? parseInt(x[i]) : 0;
            int vj = i < y.length ? parseInt(y[i]) : 0;
            if (vi != vj)
                return vi > vj ? 1 : -1;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 发起一次检测。返回 false 表示本次没有发起（已有检测在跑或页面已结束），
     * 调用方可据此避免一直等待回调。
     */
    public static boolean check(Activity act, Listener listener) {
        if (running || act == null || act.isFinishing())
            return false;
        running = true;
        Log.d(TAG, "开始检查更新，当前版本 " + currentVersion(act));
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result r;
            try {
                r = fetchLatest();
            } catch (Throwable t) {
                r = new Result();
                r.error = "检查更新失败：" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
            }
            Result fr = r;
            running = false;
            Log.d(TAG, "检查结束 latest=" + fr.latest + " 直链=" + (fr.downloadUrl != null)
                    + " error=" + fr.error + " 轨迹=" + fr.trace);
            ui.post(() -> {
                if (listener != null && !act.isFinishing())
                    listener.onChecked(fr.latest, fr.downloadUrl, fr.error);
            });
        }, "update-check").start();
        return true;
    }

    // ==================== 检测链路 ====================

    private static final class Result {
        String latest;
        String downloadUrl;
        String error;
        final List<String> trace = new ArrayList<>();
    }

    private static Result fetchLatest() throws Exception {
        Result r = new Result();
        r.trace.add("SHARE=" + SHARE_ID);

        JSONArray root = list("", r);

        // 1) 根目录找应用文件夹；找不到就把根目录当文件列表（兼容旧分享结构）
        String appId = null, appName = null;
        for (int i = 0; i < root.length(); i++) {
            JSONObject it = root.optJSONObject(i);
            if (it == null || !isFolder(it))
                continue;
            String n = nameOf(it);
            if (matches(n, APP_DIRS)) {
                appId = it.optString("folderId");
                appName = n;
                break;
            }
        }
        JSONArray appList;
        if (appId == null || appId.isEmpty()) {
            r.trace.add("APP_DIR=未找到，根目录直接作为文件列表");
            appList = root;
        } else {
            r.trace.add("APP_DIR=" + appName + "(" + appId + ")");
            appList = list(appId, r);
        }

        // 2) 应用目录里找「安卓」子文件夹；找不到给明确失败提示，不崩
        String platId = null, platName = null;
        for (int i = 0; i < appList.length(); i++) {
            JSONObject it = appList.optJSONObject(i);
            if (it == null || !isFolder(it))
                continue;
            String n = nameOf(it);
            if (matches(n, ANDROID_DIRS)) {
                platId = it.optString("folderId");
                platName = n;
                break;
            }
        }
        if (platId == null || platId.isEmpty()) {
            r.error = "分享目录里没有找到「安卓」子文件夹（当前目录只有：" + names(appList) + "）";
            return r;
        }
        r.trace.add("PLATFORM=" + platName);

        // 3) 平台目录里按扩展名 + 版本号筛，取版本号最大的 apk
        JSONArray files = list(platId, r);
        String bestVer = null, bestId = null, bestName = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject it = files.optJSONObject(i);
            if (it == null || isFolder(it))
                continue;
            String n = nameOf(it);
            Matcher m = APK_VERSION.matcher(n);
            if (!m.find())
                continue;
            String v = m.group(1);
            if (bestVer == null || compare(v, bestVer) > 0) {
                bestVer = v;
                bestId = it.optString("fileId");
                bestName = n;
            }
        }
        if (bestVer == null) {
            r.error = "「" + platName + "」目录里没有找到 apk 安装包（" + names(files) + "）";
            return r;
        }
        r.latest = bestVer;
        r.trace.add("FOUND=" + bestName);

        // 4) 取直链
        r.downloadUrl = resolve(bestId, r);
        return r;
    }

    /** 列一层目录；接口必须声明 Accept-Encoding: gzip，否则会返回空列表 */
    private static JSONArray list(String folderId, Result r) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String q = "devType=3&devModel=Windows&uuid=" + uuid()
                + "&extra=2&timestamp=" + enc(ts)
                + "&shareId=" + SHARE_ID + "&offset=1&limit=100";
        if (folderId != null && !folderId.isEmpty())
            q += "&folderId=" + folderId;
        String body = read(open(API + "/ws/share/list?" + q, "POST"), r);
        JSONObject j = new JSONObject(body);
        JSONArray arr = j.optJSONArray("list");
        if (arr == null)
            arr = new JSONArray();
        String msg = j.optString("msg", "");
        r.trace.add("LIST " + (folderId == null || folderId.isEmpty() ? "root" : folderId)
                + " total=" + j.optInt("total") + " msg=" + msg);
        if (arr.length() == 0 && !isOkMsg(msg) && !isOkMsg(j.optString("code", "")))
            throw new IOException("列目录失败：" + msg + "（HTTP 200，接口返回非成功）");
        return arr;
    }

    /** 取直链：只看 302 的 Location，不跟跳转、不读 body */
    private static String resolve(String fileId, Result r) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String q = "downloadId=" + enc(fileId + "|")
                + "&enable=1&devType=3&uuid=" + uuid()
                + "&timestamp=" + enc(ts)
                + "&auth=" + enc(fileId + "|" + ts)
                + "&shareId=" + SHARE_ID;
        HttpURLConnection c = open(API + "/ws/file/redirect?" + q, "GET");
        c.setInstanceFollowRedirects(false);
        String url = c.getURL().toString();
        int code;
        String ctype, loc;
        try {
            code = c.getResponseCode();
            ctype = c.getContentType();
            loc = c.getHeaderField("Location");
        } finally {
            c.disconnect();
        }
        r.trace.add("LINK http=" + code + " ctype=" + ctype + " loc=" + shortUrl(loc));
        if (code >= 300 && code < 400 && loc != null && !loc.isEmpty())
            return loc;
        if (code == 200 && ctype != null && ctype.contains("android.package-archive"))
            return url;
        r.error = "取下载直链失败：HTTP " + code + "，内容类型 " + ctype + "，实际地址 " + shortUrl(url);
        return null;
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        // 关键：不声明 gzip 时接口会返回 total=0 的空列表
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Origin", "https://share.feijipan.com");
        c.setRequestProperty("Referer", SHARE_URL);
        if ("POST".equals(method)) {
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        return c;
    }

    private static String read(HttpURLConnection c, Result r) throws Exception {
        int code;
        InputStream in;
        String ctype;
        String encoding;
        try {
            code = c.getResponseCode();
            ctype = c.getContentType();
            encoding = c.getContentEncoding();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (Throwable t) {
            throw new IOException("网络异常：" + t.getClass().getSimpleName());
        }
        if (in == null)
            throw new IOException("HTTP " + code + "，响应为空");
        // 手动声明了 Accept-Encoding，系统不会自动解压，必须自己解
        if (encoding != null && encoding.toLowerCase().contains("gzip"))
            in = new GZIPInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            bos.write(buf, 0, n);
        in.close();
        String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code != 200)
            throw new IOException("HTTP " + code + "，内容类型 " + ctype + "："
                    + body.substring(0, Math.min(160, body.length())));
        return body;
    }

    // ==================== 小工具 ====================

    /** AES-128-ECB + PKCS5，输出大写 hex（分享页 encryptHex 的等价实现） */
    private static String enc(String text) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] out = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out)
            sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static String uuid() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte v : b)
            sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static boolean isFolder(JSONObject it) {
        if (it.optInt("fileType", 0) == 2)
            return true;
        String name = nameOf(it);
        return !it.optString("folderId").isEmpty()
                && !name.toLowerCase().endsWith(".apk")
                && !name.toLowerCase().endsWith(".ipa");
    }

    private static String nameOf(JSONObject it) {
        String n = it.optString("fileName", "");
        if (n.isEmpty())
            n = it.optString("name", "");
        if (n.isEmpty())
            n = it.optString("folderName", "");
        return n;
    }

    private static boolean matches(String name, String[] candidates) {
        String lower = name.toLowerCase();
        for (String c : candidates)
            if (lower.contains(c.toLowerCase()))
                return true;
        return false;
    }

    private static boolean isOkMsg(String msg) {
        if (msg == null)
            return false;
        String m = msg.trim().toLowerCase();
        return m.isEmpty() || m.equals("200") || m.contains("成功") || m.equals("success") || m.equals("ok");
    }

    private static String names(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null)
                continue;
            if (sb.length() > 0)
                sb.append("、");
            sb.append(nameOf(it));
        }
        return sb.length() == 0 ? "空目录" : sb.toString();
    }

    private static String shortUrl(String url) {
        if (url == null)
            return "(无)";
        return url.length() <= 120 ? url : url.substring(0, 120) + "…";
    }

    /** 下载 APK 时带的 UA，与接口请求一致 */
    public static String userAgent() {
        return UA;
    }
}
