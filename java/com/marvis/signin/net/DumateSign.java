package com.marvis.signin.net;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.util.Http;

/**
 * 百度搭子（DuMate）签到编排。
 *
 * 一次签到 = 查状态 -> 发一次桌面端领取 -> 复查状态算积分增量。
 *
 * 关键点：
 *   1. 请求头带 X-Dumate-Client-Type: desktop，服务端才会按桌面端额度发放；
 *   2. 网页端签过 200 分后，桌面端仍可补 300，所以即使状态显示已签，
 *      也会发一次幂等领取（已领完时上游返回「已签到/已领取」，按已完成处理）；
 *   3. Cookie 失效（401/10001）直接给出重新登录提示，不反复重试。
 */
public class DumateSign {

    /** 一次签到的结论。 */
    public static class Verdict {
        public boolean ok = false;
        public boolean already = false;
        public String message = "";
    }

    /** 执行一次百度搭子桌面端签到。 */
    public static Verdict run(Platform p) {
        Verdict v = new Verdict();

        String cookie = Dumate.cookieOf(p);
        if (cookie.length() == 0) {
            v.message = "未配置 Cookie：请到配置页粘贴百度搭子网页版 Cookie（至少需含 BDUSS）";
            return v;
        }
        String csrf = Dumate.csrfOf(p, cookie);
        boolean csrfMissing = csrf.length() == 0;

        // 1) 查状态（桌面端视角）
        Dumate.Response st = Dumate.status(p, cookie, csrf);
        trace(p, st == null ? null : st.http);
        if (st == null || st.http == null) {
            v.message = "查询签到状态失败：没有返回";
            return v;
        }
        if (Dumate.authFailed(st)) {
            v.message = "登录态已失效（HTTP " + st.http.code + "），请重新登录 https://dumate.baidu.com/app 并更新 Cookie";
            return v;
        }
        if (!st.ok) {
            v.message = "查询签到状态失败：" + Dumate.errorOf(st) + csrfHint(csrfMissing);
            return v;
        }
        Dumate.Info before = Dumate.infoOf(st);

        // 2) 领取（桌面端）。已签也发一次：网页端签过 200 后，桌面端还能补 300。
        Dumate.Response cl = Dumate.claim(p, cookie, csrf);
        trace(p, cl == null ? null : cl.http);
        if (cl == null || cl.http == null) {
            v.message = "领取失败：没有返回";
            return v;
        }
        if (Dumate.authFailed(cl)) {
            v.message = "登录态已失效（HTTP " + cl.http.code + "），请重新登录 https://dumate.baidu.com/app 并更新 Cookie";
            return v;
        }

        boolean alreadyWord = looksAlready(cl.message);
        if (!cl.ok && !alreadyWord) {
            v.message = "领取失败：" + Dumate.errorOf(cl) + csrfHint(csrfMissing);
            return v;
        }

        // 3) 复查状态，算这次实际到账的积分
        Dumate.Response after = Dumate.status(p, cookie, csrf);
        trace(p, after == null ? null : after.http);
        Dumate.Info aft = after != null && after.ok ? Dumate.infoOf(after) : null;

        double delta = -1;
        if (aft != null && before.totalPoints >= 0 && aft.totalPoints >= 0) {
            delta = aft.totalPoints - before.totalPoints;
        }

        String base = "HTTP " + cl.http.code + " · " + cl.http.cost + "ms";
        boolean already = alreadyWord || (aft != null && aft.hasIssued && !(delta > 0))
                || (before.hasIssued && !(delta > 0));
        if (already) {
            v.ok = true;
            v.already = true;
            v.message = base + " · 桌面端今日已签到" + pointsText(aft != null ? aft.totalPoints : before.totalPoints);
            return v;
        }

        v.ok = true;
        v.message = base + " · 桌面端签到成功"
                + (delta > 0 ? " +" + fmt(delta) + " 积分" : "")
                + pointsText(aft != null ? aft.totalPoints : -1);
        return v;
    }

    /** 把请求记进抓包助手，和其它平台的报文放在一起。 */
    private static void trace(Platform p, Http.Result r) {
        if (Store.APP == null || p == null || r == null) {
            return;
        }
        try {
            com.marvis.signin.data.TraceStore.record(Store.APP, p, r, 0);
        } catch (Exception ignored) {
        }
    }

    /** Cookie 里没有 bce-user-info 时，给一条不阻断、可操作的提示。 */
    private static String csrfHint(boolean missing) {
        return missing
                ? "（未找到 bce-user-info；若返回 403，可把请求头里的 csrfToken 一起粘贴到配置页）"
                : "";
    }

    private static boolean looksAlready(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("已签到") || msg.contains("已领取") || msg.contains("已经领取")
                || msg.toLowerCase().contains("already");
    }

    private static String pointsText(double total) {
        return total >= 0 ? "（累计 " + fmt(total) + " 积分）" : "";
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.valueOf(v);
    }
}