package com.marvis.signin.net;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.util.Http;

/**
 * MiniMax 签到编排。
 *
 * 一次签到 = [必要时刷新 access token] -> 查状态 -> 今天可领才发 claim。
 *
 * 关键点：
 *   1. access token 只有 1 小时左右，所以按到期时间提前刷新；
 *   2. refresh token 是一次性的，刷新后上游会换新的，必须连同 access token 一起写回本地；
 *   3. 状态接口说今天不可领时，一个写请求都不发（避免无谓的领取请求）；
 *   4. 401 时刷新一次并重试一次，不无限重试。
 */
public class MinimaxSign {

    /** 提前多久刷新 access token（毫秒）。 */
    private static final long REFRESH_AHEAD_MS = 2 * 60 * 1000L;

    /** 一次签到的结论。 */
    public static class Verdict {
        public boolean ok = false;
        public boolean already = false;
        public String message = "";
    }

    /** 执行一次 MiniMax 签到。 */
    public static Verdict run(Platform p) {
        Verdict v = new Verdict();

        if (!p.hasMinimaxToken()) {
            v.message = "未授权：请点「授权 MiniMax」完成一次登录授权";
            return v;
        }

        // 1) 保证 access token 可用
        if (!ensureToken(p, v)) {
            return v;
        }

        // 2) 查签到状态
        Http.Result st = Minimax.status(p.credValue);
        trace(p, st);
        if (st.code == 401) {
            // 令牌被判失效：刷新一次再来
            if (!refreshToken(p, v, "令牌已失效")) {
                return v;
            }
            st = Minimax.status(p.credValue);
            trace(p, st);
        }
        if (st.error != null && st.error.length() > 0) {
            v.message = "查询签到状态失败：" + st.error;
            return v;
        }
        if (!st.httpOk()) {
            v.message = st.code == 401
                    ? "令牌无效（401），请重新授权"
                    : "查询签到状态失败：HTTP " + st.code + " · " + Minimax.upstreamError(st);
            return v;
        }

        // 3) 今天可领才发 claim
        // 注意：解析不出今天这一格 ≠ 今天已签到。
        // 混为一谈会导致「假成功」：记一条成功、每日补做也跳过，结果一天都领不到额度。
        org.json.JSONObject cell = Minimax.todayCell(st);
        if (cell == null) {
            v.message = "无法解析今天的签到状态（上游返回结构可能变了）：" + Minimax.upstreamError(st);
            return v;
        }
        int todayStatus = cell.optInt("status", -1);
        if (todayStatus != 2) {
            v.ok = true;
            v.already = true;
            int pts = cell.optInt("points", 0);
            v.message = "HTTP " + st.code + " · " + st.cost + "ms · 今天已签到"
                    + (pts > 0 ? "（今日 " + pts + " 算力）" : "");
            return v;
        }

        Http.Result cl = Minimax.claim(p.credValue);
        trace(p, cl);
        if (cl.code == 401) {
            if (!refreshToken(p, v, "令牌已失效")) {
                return v;
            }
            cl = Minimax.claim(p.credValue);
            trace(p, cl);
        }
        if (cl.error != null && cl.error.length() > 0) {
            v.message = "领取失败：" + cl.error;
            return v;
        }
        if (!cl.httpOk()) {
            v.message = cl.code == 401
                    ? "令牌无效（401），请重新授权"
                    : "领取失败：HTTP " + cl.code + " · " + Minimax.upstreamError(cl);
            return v;
        }

        int result = Minimax.claimResult(cl);
        int points = Minimax.claimPoints(cl);
        if (result == 1) {
            v.ok = true;
            v.message = "HTTP " + cl.code + " · " + cl.cost + "ms · 签到成功"
                    + (points > 0 ? " +" + points + " 算力" : "");
        } else if (result == 2) {
            v.ok = true;
            v.already = true;
            v.message = "HTTP " + cl.code + " · " + cl.cost + "ms · 今天已签到";
        } else {
            v.message = "领取失败：" + Minimax.upstreamError(cl);
        }
        return v;
    }

    /** access token 是否还需要用（没有或快过期都要换）。 */
    private static boolean needRefresh(Platform p) {
        if (p.credValue == null || p.credValue.trim().length() == 0) {
            return true;
        }
        if (p.credExpireAt <= 0) {
            return false; // 不知道到期时间就先试，401 时再刷新
        }
        return System.currentTimeMillis() + REFRESH_AHEAD_MS >= p.credExpireAt;
    }

    /** 保证有可用的 access token。 */
    private static boolean ensureToken(Platform p, Verdict v) {
        if (!needRefresh(p)) {
            return true;
        }
        return refreshToken(p, v, p.credValue == null || p.credValue.trim().length() == 0
                ? "尚未获取访问令牌" : "访问令牌已过期");
    }

    /** 把这次请求记进抓包助手，和其它平台的报文放在一起。 */
    private static void trace(Platform p, Http.Result r) {
        if (Store.APP == null || p == null || r == null) {
            return;
        }
        try {
            com.marvis.signin.data.TraceStore.record(Store.APP, p, r, 0);
        } catch (Exception ignored) {
        }
    }

    /** 用 refresh token 换新令牌，并立刻落盘（refresh token 会轮换，丢了就再也续不上）。 */
    private static boolean refreshToken(Platform p, Verdict v, String reason) {
        String rt = p.credRefresh == null ? "" : p.credRefresh.trim();
        if (rt.length() == 0) {
            v.message = reason + "，且没有可用的刷新令牌，请重新授权";
            return false;
        }
        try {
            Minimax.Token t = Minimax.refresh(rt);
            p.credValue = t.accessToken;
            if (t.refreshToken != null && t.refreshToken.trim().length() > 0) {
                p.credRefresh = t.refreshToken; // 上游轮换了新的 refresh token
            }
            p.credExpireAt = System.currentTimeMillis()
                    + Math.max(t.expiresIn, 60) * 1000L;
            p.credIssuedAt = System.currentTimeMillis();
            p.lastWarnAt = 0L;
            if (Store.APP != null) {
                new Store(Store.APP).upsert(p);
            }
            return true;
        } catch (Exception e) {
            v.message = reason + "，刷新失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
            return false;
        }
    }
}
