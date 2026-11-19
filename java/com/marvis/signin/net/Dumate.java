package com.marvis.signin.net;

import com.marvis.signin.model.Platform;
import com.marvis.signin.util.Http;
import com.marvis.signin.util.Vars;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLDecoder;

/**
 * 百度搭子（DuMate）签到协议层。
 *
 * 关键点：
 *   1. 网页端签到只有 200 积分，桌面端签到可领 500（网页已签则补 300）；
 *   2. 服务端靠请求头 X-Dumate-Client-Type 区分客户端，桌面端固定发 "desktop"；
 *   3. 鉴权用网页版 Cookie，CSRF 取 Cookie 里的 bce-user-info（URL 解码后放 csrfToken 头）；
 *   4. 两步签到：先查 loginBonusInfo，今天这一端没签才发 loginBonus。
 */
public class Dumate {

    public static final String DEFAULT_BASE = "https://dumate.baidu.com";
    public static final String STATUS_PATH = "/api/dumate/points/loginBonusInfo";
    public static final String CLAIM_PATH = "/api/dumate/points/loginBonus";
    /** 桌面客户端标识：服务端据此按桌面端额度发放。 */
    public static final String CLIENT_TYPE_DESKTOP = "desktop";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 接口返回的签到信息。 */
    public static class Info {
        public boolean hasIssued = false;
        public int signInDays = 0;
        public double totalPoints = -1;
        public int totalTimes = -1;
        public String toast = "";
    }

    /** 一次接口调用的解析结果。 */
    public static class Response {
        public Http.Result http;
        public int code = -1;
        public String message = "";
        public JSONObject result;
        public boolean ok = false;
    }

    /** 从平台配置里找 Cookie：优先「当前凭据」，其次请求头里的 Cookie 行。 */
    public static String cookieOf(Platform p) {
        if (p == null) {
            return "";
        }
        String cred = extractCookieLine(p.credValue);
        if (isUsableCookie(cred)) {
            return cred;
        }
        String headers = p.headers == null ? "" : p.headers;
        String filled = Vars.fill(headers, "", "", p.credValue);
        String[] lines = filled.split("\n");
        for (String line : lines) {
            String s = line.trim();
            int idx = s.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            if (!"Cookie".equalsIgnoreCase(s.substring(0, idx).trim())) {
                continue;
            }
            String value = extractCookieLine(s.substring(idx + 1).trim());
            if (isUsableCookie(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 从用户粘贴的内容里取出 Cookie：
     * - 纯 Cookie 串直接用；
     * - 从抓包复制的多行文本（含 Cookie: 与 csrfToken: 两行）只取 Cookie 行；
     * - 被换行拆断的 Cookie 自动拼回分号分隔。
     */
    private static String extractCookieLine(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() == 0) {
            return "";
        }
        String[] lines = normalized.split("\n");
        for (String line : lines) {
            String s = line.trim();
            if (s.toLowerCase().startsWith("cookie:")) {
                return normalizeCookie(s.substring(7));
            }
        }
        return normalizeCookie(normalized);
    }

    /** 把换行/多余分号整理成合法的 Cookie 头。 */
    private static String normalizeCookie(String text) {
        if (text == null) {
            return "";
        }
        String v = text.trim();
        if (v.toLowerCase().startsWith("cookie:")) {
            v = v.substring(7).trim();
        }
        v = v.replace('\r', ';').replace('\n', ';');
        v = v.replaceAll(";\\s*;", ";");
        while (v.startsWith(";")) {
            v = v.substring(1).trim();
        }
        while (v.endsWith(";")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v;
    }

    /**
     * 取 csrfToken：优先 Cookie 里的 bce-user-info；
     * 用户把请求头 csrfToken 单独粘贴进来时，用请求头里的值兜底。
     */
    public static String csrfOf(Platform p, String cookie) {
        String csrf = csrfOf(cookie);
        if (csrf.length() == 0 && p != null) {
            csrf = headerValue(p.headers, "csrfToken");
        }
        return csrf;
    }

    /** 读取自定义请求头里的值，没有/还是占位符时返回空串。 */
    public static String headerValue(String headers, String name) {
        if (headers == null || name == null) {
            return "";
        }
        String[] lines = headers.split("\n");
        for (String line : lines) {
            String s = line.trim();
            int idx = s.indexOf(':');
            if (idx <= 0 || !name.equalsIgnoreCase(s.substring(0, idx).trim())) {
                continue;
            }
            String value = s.substring(idx + 1).trim();
            if (value.length() == 0 || value.contains("{{")) {
                return "";
            }
            return value;
        }
        return "";
    }

    /** 从 Cookie 串里取 bce-user-info，URL 解码后作为 csrfToken。 */
    public static String csrfOf(String cookie) {
        String raw = cookieValue(normalizeCookie(cookie), "bce-user-info");
        if (raw.length() == 0) {
            return "";
        }
        String v = raw;
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            // URLDecoder 会把 + 当成空格，Cookie 值里的 + 先转义掉
            v = URLDecoder.decode(v.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 取 Cookie 里的某个字段值，没有返回空串。 */
    public static String cookieValue(String cookie, String name) {
        if (cookie == null || name == null) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            if (name.equals(part.substring(0, idx).trim())) {
                return part.substring(idx + 1).trim();
            }
        }
        return "";
    }

    /** 由签到 URL 推导接口根地址；只允许百度域名，避免 Cookie 被发到别处。 */
    public static String baseOf(Platform p) {
        String url = p == null ? "" : (p.url == null ? "" : p.url.trim());
        if (url.startsWith("https://")) {
            try {
                URL u = new URL(url);
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                if (host.equals("dumate.baidu.com") || host.endsWith(".dumate.baidu.com")
                        || host.equals("dumate.cn") || host.endsWith(".dumate.cn")
                        || host.equals("console.bce.baidu.com")) {
                    String port = u.getPort() > 0 ? ":" + u.getPort() : "";
                    return "https://" + u.getHost() + port;
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_BASE;
    }

    /** 组装请求头：Cookie + csrfToken + 桌面端标识 + 控制台同款 Origin/Referer。 */
    public static String headerText(String cookie, String csrf, String base) {
        return "Cookie: " + cookie + "\n"
                + "csrfToken: " + csrf + "\n"
                + "X-Dumate-Client-Type: " + CLIENT_TYPE_DESKTOP + "\n"
                + "lang: zh\n"
                + "Accept: application/json, text/plain, */*\n"
                + "Content-Type: application/json;charset=UTF-8\n"
                + "Origin: " + base + "\n"
                + "Referer: " + base + "/app\n"
                + "User-Agent: " + UA;
    }

    /** 查询签到状态（桌面端视角）。 */
    public static Response status(Platform p, String cookie, String csrf) {
        String base = baseOf(p);
        Http.Result r = Http.execute(base + STATUS_PATH, "GET",
                headerText(cookie, csrf, base), null, 20000, null);
        return parse(r);
    }

    /** 领取签到积分（桌面端视角）。 */
    public static Response claim(Platform p, String cookie, String csrf) {
        String base = baseOf(p);
        Http.Result r = Http.execute(base + CLAIM_PATH, "POST",
                headerText(cookie, csrf, base), "{}", 20000, null);
        return parse(r);
    }

    /** 解析 {code, message, data:{result:{...}}} 结构。 */
    public static Response parse(Http.Result r) {
        Response resp = new Response();
        resp.http = r;
        if (r == null) {
            resp.message = "请求没有返回";
            return resp;
        }
        if (r.error != null && r.error.length() > 0) {
            resp.message = r.error;
            return resp;
        }
        if (r.body == null || r.body.trim().length() == 0) {
            resp.message = "响应为空（HTTP " + r.code + "）";
            return resp;
        }
        try {
            JSONObject root = new JSONObject(r.body);
            boolean hasCode = root.has("code");
            resp.code = hasCode ? root.optInt("code", -1)
                    : (root.optBoolean("success", true) ? 0 : -1);
            JSONObject data = root.optJSONObject("data");
            JSONObject result = data;
            if (data != null && data.opt("result") instanceof JSONObject) {
                result = data.optJSONObject("result");
            }
            resp.result = result;
            resp.message = messageOf(root, data);
            resp.ok = r.httpOk() && resp.code == 0;
        } catch (Exception e) {
            resp.message = "响应不是合法 JSON：" + cut(r.body, 80);
            resp.ok = false;
        }
        return resp;
    }

    /** 从解析结果里取签到信息。 */
    public static Info infoOf(Response resp) {
        Info info = new Info();
        JSONObject o = resp == null ? null : resp.result;
        if (o == null) {
            return info;
        }
        info.hasIssued = o.optBoolean("hasIssued", false);
        JSONArray days = o.optJSONArray("signInDays");
        info.signInDays = days == null ? 0 : days.length();
        if (o.has("totalPoints")) {
            info.totalPoints = o.optDouble("totalPoints", -1);
        }
        if (o.has("totalTimes")) {
            info.totalTimes = o.optInt("totalTimes", -1);
        }
        info.toast = o.optString("issuedToast", "");
        return info;
    }

    /** 错误文案：优先服务端 message，其次 data.error.message。 */
    public static String errorOf(Response resp) {
        if (resp == null) {
            return "未知错误";
        }
        if (resp.message != null && resp.message.length() > 0) {
            return resp.message;
        }
        if (resp.http != null) {
            return "HTTP " + resp.http.code;
        }
        return "未知错误";
    }

    /** 登录态失效：HTTP 401/403 或业务码 10001。 */
    public static boolean authFailed(Response resp) {
        if (resp == null || resp.http == null) {
            return false;
        }
        // 403 可能是 CSRF 校验失败，不直接当成登录过期，交给上层给 csrf 提示
        if (resp.http.code == 401 || resp.code == 10001) {
            return true;
        }
        String m = resp.message == null ? "" : resp.message.toLowerCase();
        return m.contains("登录已经过期") || m.contains("未登录") || m.contains("登录已过期");
    }

    private static String messageOf(JSONObject root, JSONObject data) {
        String msg = root.optString("message", "");
        if (msg.length() == 0) {
            msg = root.optString("msg", "");
        }
        if (msg.length() == 0 && data != null) {
            JSONObject err = data.optJSONObject("error");
            if (err != null) {
                msg = err.optString("message", "");
            }
        }
        return msg;
    }

    private static boolean isUsableCookie(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.length() == 0 || v.indexOf('=') <= 0) {
            return false;
        }
        String lower = v.toLowerCase();
        return !(lower.contains("填你的") || lower.contains("你的cookie")
                || lower.contains("在此填写") || lower.contains("请填写"));
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}