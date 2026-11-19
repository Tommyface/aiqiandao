package com.marvis.signin.net;

import android.util.Base64;

import com.marvis.signin.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MiniMax Code（海螺）协议层。
 *
 * 授权：RFC 8628 OAuth 2.0 设备码流程
 *   1. POST account.minimax.cn/oauth2/device/code  -> device_code + 浏览器验证链接
 *   2. 用户在浏览器里登录并确认授权
 *   3. 轮询 account.minimax.cn/oauth2/token         -> access_token + refresh_token
 *
 * 签到：与网页版一致的两步接口
 *   GET  /minimax-cloud/api/v1/signin/status  -> 看今天能不能领（status == 2 才可领）
 *   POST /minimax-cloud/api/v1/signin/claim   -> 领取（上游自带幂等，claim_result: 1 领取成功 / 2 已领取）
 *
 * 令牌轮换：refresh token 一次性，刷新后上游会换新的，必须把新的 refresh token 一起写回本地。
 */
public class Minimax {

    public static final String ACCOUNT_BASE = "https://account.minimax.cn";
    public static final String SIGNIN_BASE = "https://agent.minimax.cn/minimax-cloud/api/v1/signin";
    public static final String CLIENT_ID = "mcode-public";
    public static final String SCOPE = "agent.default";
    public static final String AUDIENCE = "agent-backend";

    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final String TIMEZONE = "Asia/Shanghai";

    /** 设备码申请结果。 */
    public static class DeviceCode {
        public String deviceCode = "";
        public String userCode = "";
        public String verificationUri = "";
        public String verificationUriComplete = "";
        public int expiresIn = 0;
        public int interval = 3;
        /** PKCE 的 verifier：换令牌时必须原样带回。 */
        public String verifier = "";
    }

    /** 令牌结果。 */
    public static class Token {
        public String accessToken = "";
        public String refreshToken = "";
        public int expiresIn = 0;
    }

    /** 授权流程里的可预期错误（提示文案直接给用户看）。 */
    public static class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    // ---------------- 授权 ----------------

    /** 申请设备码：返回浏览器验证链接，用户点开确认后即可换令牌。 */
    public static DeviceCode requestDeviceCode() throws AuthException {
        String verifier = randomVerifier();
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        form.put("audience", AUDIENCE);
        form.put("code_challenge", challenge(verifier));
        form.put("code_challenge_method", "S256");

        Http.Result r = Http.executeForm(ACCOUNT_BASE + "/oauth2/device/code", form, 20000);
        if (r.error != null && r.error.length() > 0) {
            throw new AuthException("申请设备码失败：" + r.error);
        }
        JSONObject o = parse(r.body);
        if (o == null) {
            throw new AuthException("设备码响应异常：" + cut(r.body, 120));
        }
        DeviceCode dc = new DeviceCode();
        dc.deviceCode = o.optString("device_code", "");
        dc.userCode = o.optString("user_code", "");
        dc.verificationUri = o.optString("verification_uri", "");
        dc.verificationUriComplete = o.optString("verification_uri_complete", "");
        dc.expiresIn = o.optInt("expires_in", 600);
        dc.interval = Math.max(o.optInt("interval", 3), 2);
        dc.verifier = verifier;
        if (dc.deviceCode.length() == 0) {
            throw new AuthException("设备码响应缺少 device_code：" + cut(r.body, 120));
        }
        if (dc.verificationUriComplete.length() == 0) {
            dc.verificationUriComplete = dc.verificationUri;
        }
        return dc;
    }

    /**
     * 轮询换令牌。
     *
     * @return 用户尚未确认时返回 null（继续等）；已确认返回 Token。
     */
    public static Token pollToken(DeviceCode dc) throws AuthException {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", DEVICE_GRANT);
        form.put("device_code", dc.deviceCode);
        form.put("client_id", CLIENT_ID);
        form.put("code_verifier", dc.verifier);

        Http.Result r = Http.executeForm(ACCOUNT_BASE + "/oauth2/token", form, 20000);
        if (r.error != null && r.error.length() > 0) {
            throw new AuthException("换令牌失败：" + r.error);
        }
        JSONObject o = parse(r.body);
        if (o == null) {
            throw new AuthException("令牌响应异常：" + cut(r.body, 120));
        }
        String signal = (o.optString("error", "") + " " + o.optString("status", "")).toLowerCase();
        if (signal.contains("authorization_pending") || signal.contains("pending")) {
            return null;
        }
        if (signal.contains("slow_down")) {
            // 上游让我们放慢：把间隔拉长再继续等
            dc.interval = Math.min(dc.interval + 5, 60);
            return null;
        }
        if (signal.contains("access_denied") || signal.contains("denied")) {
            throw new AuthException("授权被拒绝");
        }
        if (signal.contains("expired_token")) {
            throw new AuthException("设备码已过期，请重新发起授权");
        }
        String access = o.optString("access_token", "");
        if (access.length() == 0) {
            throw new AuthException("令牌响应缺少 access_token：" + cut(r.body, 120));
        }
        return tokenOf(o);
    }

    /**
     * 用 refresh token 换新的 access token。
     * 上游会同时轮换 refresh token，返回值里必须把新的 refresh token 一起存回去。
     */
    public static Token refresh(String refreshToken) throws AuthException {
        if (refreshToken == null || refreshToken.trim().length() == 0) {
            throw new AuthException("缺少 refresh token，请重新授权");
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken.trim());
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        form.put("audience", AUDIENCE);

        Http.Result r = Http.executeForm(ACCOUNT_BASE + "/oauth2/token", form, 20000);
        if (r.error != null && r.error.length() > 0) {
            throw new AuthException("刷新令牌失败：" + r.error);
        }
        JSONObject o = parse(r.body);
        if (o == null) {
            throw new AuthException("刷新响应异常：" + cut(r.body, 120));
        }
        String access = o.optString("access_token", "");
        if (access.length() == 0) {
            String err = o.optString("error_description", o.optString("error", ""));
            throw new AuthException(err.length() > 0 ? err : ("刷新响应异常：" + cut(r.body, 120)));
        }
        return tokenOf(o);
    }

    private static Token tokenOf(JSONObject o) {
        Token t = new Token();
        t.accessToken = o.optString("access_token", "");
        t.refreshToken = o.optString("refresh_token", "");
        t.expiresIn = o.optInt("expires_in", 3600);
        return t;
    }

    // ---------------- 签到 ----------------

    /** 查今天的签到面板。 */
    public static Http.Result status(String accessToken) {
        return Http.execute(SIGNIN_BASE + "/status?timezone_id=" + enc(TIMEZONE),
                "GET", authHeaders(accessToken), null, 20000, null);
    }

    /** 领取今日额度。 */
    public static Http.Result claim(String accessToken) {
        return Http.execute(SIGNIN_BASE + "/claim?timezone_id=" + enc(TIMEZONE),
                "POST", authHeaders(accessToken), "{}", 20000, null);
    }

    private static String authHeaders(String accessToken) {
        return "Authorization: Bearer " + (accessToken == null ? "" : accessToken.trim())
                + "\nAccept: application/json";
    }

    /** 今天那一格：status == 2 表示今天可以领。 */
    public static JSONObject todayCell(Http.Result r) {
        JSONObject root = parse(r == null ? null : r.body);
        if (root == null) {
            return null;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONArray days = data.optJSONArray("days");
        if (days == null) {
            return null;
        }
        // 先按 is_today 标记找
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d != null && isToday(d)) {
                return d;
            }
        }
        // 兜底：有些版本返回的是日期字符串，按北京时间当天匹配
        String today = todayDateString();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) {
                continue;
            }
            String date = d.optString("date", "");
            if (date.length() > 0 && today.equals(date.substring(0, Math.min(10, date.length())))) {
                return d;
            }
        }
        return null;
    }

    /** is_today 兼容 true / 1 / "1" / "true" 等多种写法。 */
    private static boolean isToday(JSONObject d) {
        Object v = d.opt("is_today");
        if (v == null || v == JSONObject.NULL) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() == 1;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return "1".equals(s) || "true".equals(s);
    }

    /** 北京时间当天的日期字符串（yyyy-MM-dd）。 */
    private static String todayDateString() {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            return f.format(new java.util.Date());
        } catch (Exception e) {
            return "";
        }
    }

    /** 今天是否可领（status == 2）。 */
    public static boolean todayClaimable(Http.Result r) {
        JSONObject cell = todayCell(r);
        return cell != null && cell.optInt("status", 0) == 2;
    }

    /** 领取结果：1 = 刚领到，2 = 今天已经领过。 */
    public static int claimResult(Http.Result r) {
        JSONObject root = parse(r == null ? null : r.body);
        if (root == null) {
            return 0;
        }
        JSONObject base = root.optJSONObject("base_resp");
        if (base != null && base.optInt("code", 0) != 0) {
            return 0;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return 0;
        }
        return data.optInt("claim_result", 0);
    }

    /** 领取到的积分。 */
    public static int claimPoints(Http.Result r) {
        JSONObject root = parse(r == null ? null : r.body);
        if (root == null) {
            return 0;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return 0;
        }
        return data.optInt("points", 0);
    }

    /** 上游返回的错误原因（base_resp.code != 0 时取 msg）。 */
    public static String upstreamError(Http.Result r) {
        String body = r == null ? "" : (r.body == null ? "" : r.body);
        JSONObject root = parse(body);
        if (root == null) {
            return cut(body, 120);
        }
        JSONObject base = root.optJSONObject("base_resp");
        if (base != null) {
            int code = base.optInt("code", 0);
            if (code != 0) {
                return "code " + code + "：" + base.optString("msg", "上游拒绝");
            }
        }
        return cut(body, 120);
    }

    // ---------------- 工具 ----------------

    private static JSONObject parse(String body) {
        if (body == null || body.trim().length() == 0) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String randomVerifier() {
        try {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            return Base64.encodeToString(raw, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (Exception e) {
            return "mcodeverifier0123456789abcdefghijklmnop";
        }
    }

    /** PKCE：S256 = base64url(sha256(verifier))。 */
    private static String challenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(verifier.getBytes("UTF-8"));
            return Base64.encodeToString(d, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (Exception e) {
            return verifier;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
