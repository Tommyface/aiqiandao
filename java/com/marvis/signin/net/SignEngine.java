package com.marvis.signin.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.model.Record;
import com.marvis.signin.util.Cred;
import com.marvis.signin.util.Http;
import com.marvis.signin.util.Notify;
import com.marvis.signin.util.Vars;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 签到执行引擎：顺序发包，失败不抛异常，全部结果落本地。 */
public class SignEngine {

    public interface Callback {
        void onOne(Platform p, boolean ok, String message);

        void onAllDone(int success, int total);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running = false;
    /** 正在签到的平台 id：同一个平台同一时刻只允许一个签到流程。
     *  原因：MiniMax 的 refresh token 是一次性的，并发刷新会互相踩（invalid_grant，甚至被上游吊销整个令牌族）。 */
    private static final Set<String> ACTIVE = new HashSet<String>();

    /** 一次签到的结论：成功与否 + 展示文案。 */
    public static class Outcome {
        public boolean ok = false;
        /** 今天是否已经签过（用于主界面区分「签到成功」和「已签到」）。 */
        public boolean already = false;
        public String message = "";
    }

    public static boolean isRunning() {
        return running;
    }

    /** 单个平台（用于测试按钮 / 条目上的「签到」按钮）。 */
    public static void signOne(final Context ctx, final Platform p, final Callback cb) {
        Store.APP = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Outcome o = runPlatform(p);
                save(ctx, p, o.ok, o.message);
                if (cb != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onOne(p, o.ok, o.message);
                        }
                    });
                }
            }
        }).start();
    }

    /** 全部启用平台。 */
    public static void signAll(final Context ctx, final List<Platform> platforms, final Callback cb) {
        Store.APP = ctx.getApplicationContext();
        signInternal(ctx, platforms, cb);
    }

    /** 只签今天还没成功的平台（漏签补做）。 */
    public static void signPending(final Context ctx, final List<Platform> platforms, final Callback cb) {
        Store.APP = ctx.getApplicationContext();
        Store store = new Store(ctx);
        Set<String> done = store.todaySuccessIds();
        List<Platform> pending = new ArrayList<Platform>();
        for (Platform p : platforms) {
            if (p.enabled && !done.contains(p.id)) {
                pending.add(p);
            }
        }
        signInternal(ctx, pending, cb);
    }

    private static void signInternal(final Context ctx, final List<Platform> targets, final Callback cb) {
        if (targets == null || targets.isEmpty()) {
            if (cb != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onAllDone(0, 0);
                    }
                });
            }
            return;
        }
        if (running) {
            if (cb != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onAllDone(0, 0);
                    }
                });
            }
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int okCount = 0;
                try {
                    for (int i = 0; i < targets.size(); i++) {
                        final Platform p = targets.get(i);
                        final Outcome o = runPlatform(p);
                        final boolean ok = o.ok;
                        final String msg = o.message;
                        if (ok) {
                            okCount++;
                        }
                        save(ctx, p, ok, msg);
                        if (cb != null) {
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    cb.onOne(p, ok, msg);
                                }
                            });
                        }
                        // 相邻平台之间稍作间隔，避免密集请求
                        if (i < targets.size() - 1) {
                            try {
                                Thread.sleep(600 + (long) (Math.random() * 500));
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    final int total = targets.size();
                    final int okFinal = okCount;
                    if (cb != null) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onAllDone(okFinal, total);
                            }
                        });
                    }
                } finally {
                    // 无论是否抛异常都要解锁，否则一次异常就让「全部签到」永久卡住
                    running = false;
                }
            }
        }).start();
    }

    /**
     * 执行一个平台，并做并发互斥。
     * 同一个平台同一时刻只跑一个流程：MiniMax 的 refresh token 是一次性的，
     * 两个线程同时拿同一个 RT 去刷新会互相踩（invalid_grant 甚至吊销整族令牌）。
     */
    private static Outcome runPlatform(Platform p) {
        synchronized (ACTIVE) {
            if (!ACTIVE.add(p.id)) {
                Outcome o = new Outcome();
                o.ok = false;
                o.message = "该平台正在签到中，已跳过";
                return o;
            }
        }
        try {
            return run(p);
        } catch (Throwable t) {
            Outcome o = new Outcome();
            o.ok = false;
            o.message = "执行异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return o;
        } finally {
            synchronized (ACTIVE) {
                ACTIVE.remove(p.id);
            }
        }
    }

    /**
     * 执行一个平台的签到。
     * MiniMax 走专用流程（OAuth 令牌自动轮换 + status→claim 两步判定），
     * Trae 走专用流程（本机解密登录态 + x-device-id + status→claim 两步判定），
     * 百度搭子走专用流程（网页 Cookie + 桌面端请求头 + status→claim），
     * TeleAgent 走专用流程（X-Token 鉴权 + 每日任务→签到 + 福利社活动批量领取），
     * 其它平台走通用模板引擎。
     */
    private static Outcome run(Platform p) {
        Outcome o = new Outcome();
        if (p.isMinimax()) {
            MinimaxSign.Verdict v = MinimaxSign.run(p);
            o.ok = v.ok;
            o.already = v.already;
            o.message = v.message;
            return o;
        }
        if (p.isTrae()) {
            TraeSign.Verdict v = TraeSign.run(p);
            o.ok = v.ok;
            o.already = v.already;
            o.message = v.message;
            return o;
        }
        if (p.isDumate()) {
            DumateSign.Verdict v = DumateSign.run(p);
            o.ok = v.ok;
            o.already = v.already;
            o.message = v.message;
            return o;
        }
        if (p.isTeleagent()) {
            TeleAgentSign.Verdict v = TeleAgentSign.run(p);
            o.ok = v.ok;
            o.already = v.already;
            o.message = v.message;
            return o;
        }
        Http.Result r = doRequest(p);
        boolean ok = judge(p, r);
        o.ok = ok;
        o.already = alreadyDone(p, r);
        o.message = describe(p, r, ok);
        return o;
    }

    private static Http.Result doRequest(Platform p) {
        if (p.url == null || p.url.trim().length() == 0) {
            Http.Result r = new Http.Result();
            r.error = "未配置签到接口 URL";
            return r;
        }

        // 1) 到点主动续期（周期为 0 时跳过，只在失败时被动续期）
        boolean renewed = false;
        long now = System.currentTimeMillis();
        if (p.renewDue(now) || (p.hasRenew() && Cred.inWarnWindow(p, now))) {
            renewed = renew(p);
        }

        // 2) 正式签到
        Http.Result r = send(p);
        boolean ok = judge(p, r);

        // 3) 鉴权类失败 -> 续期一次后重试（只重试一次，避免刷接口）
        if (!ok && !renewed && p.hasRenew() && authFailed(r)) {
            if (renew(p)) {
                Http.Result second = send(p);
                second.reqDump = "[已自动续期凭据后重试]\n" + (second.reqDump == null ? "" : second.reqDump);
                r = second;
            }
        }
        return r;
    }

    /** 发一次签到请求：先跑前置请求吸 Cookie，再做模板变量替换。 */
    private static Http.Result send(Platform p) {
        String cookie = com.marvis.signin.util.CookieJar.header(Store.APP, p.id);

        // 前置请求：先 GET 一次状态接口，吸收 Set-Cookie 并提取响应体供模板变量取值
        String preBody = "";
        if (p.preUrl != null && p.preUrl.trim().length() > 0) {
            String preHeaders = Vars.fill(p.headers == null ? "" : p.headers, cookie, "", p.credValue);
            Http.Result pre = Http.execute(p.preUrl.trim(), "GET", preHeaders, null, 20000, cookie);
            preBody = pre.body == null ? "" : pre.body;
            com.marvis.signin.util.CookieJar.absorb(Store.APP, p.id, pre.setCookies);
            cookie = com.marvis.signin.util.CookieJar.header(Store.APP, p.id);
        }

        // 模板变量替换：{{token}} / {{cookie:名字}} / {{pre:$.a.b}} / {{pre:raw}}
        String url = Vars.fill(p.url.trim(), cookie, preBody, p.credValue);
        String headers = Vars.fill(p.headers == null ? "" : p.headers, cookie, preBody, p.credValue);
        String body = Vars.fill(p.body == null ? "" : p.body, cookie, preBody, p.credValue);

        Http.Result r = Http.execute(url, p.method, headers, body, 20000, cookie);
        int ck = com.marvis.signin.util.CookieJar.absorb(Store.APP, p.id, r.setCookies);
        if (p.preUrl != null && p.preUrl.trim().length() > 0) {
            r.reqDump = "[前置] GET " + p.preUrl.trim()
                    + "\n[前置响应] " + (preBody.length() > 500 ? preBody.substring(0, 500) : preBody)
                    + "\n" + (r.reqDump == null ? "" : r.reqDump);
        }
        com.marvis.signin.data.TraceStore.record(Store.APP, p, r, ck);
        return r;
    }

    /** 续期凭据：调 renewUrl，按 renewExtract 取出新值写回平台。 */
    public static boolean renew(Platform p) {
        if (!p.hasRenew()) {
            return false;
        }
        String cookie = com.marvis.signin.util.CookieJar.header(Store.APP, p.id);
        String url = Vars.fill(p.renewUrl.trim(), cookie, "", p.credValue);
        String rawHeaders = (p.renewHeaders == null || p.renewHeaders.trim().length() == 0)
                ? (p.headers == null ? "" : p.headers) : p.renewHeaders;
        String headers = Vars.fill(rawHeaders, cookie, "", p.credValue);
        String body = Vars.fill(p.renewBody == null ? "" : p.renewBody, cookie, "", p.credValue);
        String method = (p.renewMethod == null || p.renewMethod.trim().length() == 0) ? "POST" : p.renewMethod;

        Http.Result r = Http.execute(url, method, headers, body, 20000, cookie);
        int ck = com.marvis.signin.util.CookieJar.absorb(Store.APP, p.id, r.setCookies);

        String got = "";
        String ex = p.renewExtract == null ? "" : p.renewExtract.trim();
        if ("@set-cookie".equalsIgnoreCase(ex) || "@cookie".equalsIgnoreCase(ex)) {
            // 让服务端 Set-Cookie 直接成为新凭据
            got = com.marvis.signin.util.CookieJar.header(Store.APP, p.id);
        } else if (ex.length() > 0) {
            got = Vars.jsonPick(r.body, ex);
        }

        boolean success = (r.error == null || r.error.length() == 0) && got.length() > 0;
        if (success) {
            p.credValue = got;
            p.lastRenewAt = System.currentTimeMillis();
            p.credIssuedAt = System.currentTimeMillis();
            p.lastWarnAt = 0L;
            new Store(Store.APP).upsert(p);
        }

        r.reqDump = "[续期] " + method + " " + url + "\n[续期结果] "
                + (success ? "凭据已更新（" + mask(got) + "）" : "续期失败：" + cut(r.error == null ? "" : r.error, 80))
                + "\n" + (r.reqDump == null ? "" : r.reqDump);
        com.marvis.signin.data.TraceStore.record(Store.APP, p, r, ck);
        return success;
    }

    /** 鉴权失效的判定：401/403，或响应里出现过期/未登录类字样。 */
    private static boolean authFailed(Http.Result r) {
        if (r == null) {
            return false;
        }
        if (r.code == 401 || r.code == 403) {
            return true;
        }
        String b = r.body == null ? "" : r.body.toLowerCase();
        return b.contains("expired") || b.contains("unauthorized") || b.contains("invalid token")
                || b.contains("token失效") || b.contains("未登录") || b.contains("登录已过期")
                || b.contains("not login") || b.contains("auth failed");
    }

    /** 日志里只露凭据的头尾，避免明文落盘。 */
    private static String mask(String v) {
        if (v == null || v.length() == 0) {
            return "";
        }
        if (v.length() <= 12) {
            return "****";
        }
        return v.substring(0, 6) + "****" + v.substring(v.length() - 4);
    }

    private static boolean judge(Platform p, Http.Result r) {
        if (r.error != null && r.error.length() > 0) {
            return false;
        }
        if (alreadyDone(p, r)) {
            return true;
        }
        String kw = p.keyword == null ? "" : p.keyword.trim();
        if (kw.length() == 0) {
            return r.httpOk();
        }
        // 关键词用 | 分隔，命中任一即成功；4xx 里也常是「今天已签到」，故只排除 5xx
        if (r.code >= 500 || r.body == null) {
            return false;
        }
        for (String one : kw.split("\\|")) {
            String k = one.trim();
            if (k.length() > 0 && r.body.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 已签到判定：响应命中「已签到关键词」（| 分隔任一）即视为今天已完成，按成功处理。 */
    private static boolean alreadyDone(Platform p, Http.Result r) {
        if (r == null || (r.error != null && r.error.length() > 0)) {
            return false;
        }
        String kw = p.doneKeyword == null ? "" : p.doneKeyword.trim();
        if (kw.length() == 0 || r.code >= 500 || r.body == null) {
            return false;
        }
        for (String one : kw.split("\\|")) {
            String k = one.trim();
            if (k.length() > 0 && r.body.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Platform p, Http.Result r, boolean ok) {
        if (r.error != null && r.error.length() > 0) {
            return "请求失败 " + cut(r.error, 60);
        }
        String base = "HTTP " + r.code + " · " + r.cost + "ms";
        if (ok) {
            return base + (alreadyDone(p, r) ? " · 已签到" : " · 签到成功");
        }
        String snippet = cut(r.body == null ? "" : r.body.replaceAll("\\s+", " ").trim(), 60);
        return base + (snippet.length() > 0 ? " · " + snippet : " · 签到失败");
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void save(Context ctx, Platform p, boolean ok, String msg) {
        if (!ok && msg != null && msg.contains("未配置")) {
            return;
        }
        Record rec = new Record();
        rec.platformId = p.id;
        rec.platformName = p.name;
        rec.success = ok;
        rec.time = System.currentTimeMillis();
        rec.message = msg;
        new Store(ctx).addRecord(rec);
        warnIfNeeded(ctx, p);
    }

    /** 凭据进入到期前 2 天窗口（或已过期）时提醒一次，同一凭据 6 小时内不重复弹。 */
    private static void warnIfNeeded(Context ctx, Platform p) {
        long now = System.currentTimeMillis();
        if (!Cred.warnNeeded(p, now)) {
            return;
        }
        Notify.result(ctx, p.name + " 凭据即将到期", Cred.warnText(p));
        p.lastWarnAt = now;
        new Store(ctx).upsert(p);
    }
}
