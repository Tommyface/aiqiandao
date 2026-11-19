package com.marvis.signin.model;

import org.json.JSONObject;

import java.util.UUID;

/** 一个可签到的平台：本质是一条可配置的 HTTP 请求。 */
public class Platform {

    public String id = "";
    public String name = "";
    public String url = "";
    public String preUrl = "";
    public String method = "GET";
    public String headers = "";
    public String body = "";
    public String keyword = "";
    /** 已签到关键词：响应命中任一词（| 分隔）时按成功处理，结果提示「已签到」。 */
    public String doneKeyword = "";
    public boolean enabled = true;

    /**
     * 平台类型：空串 = 通用 HTTP 模板（一条可配置的请求）；
     * "minimax" = MiniMax 专用流程（OAuth 设备码授权 + 令牌自动轮换 + status→claim 两步签到）；
     * "trae" = Trae CN 专用流程（本机解密 storage.json + x-device-id + status→claim 两步签到）；
     * "dumate" = 百度搭子专用流程（网页 Cookie + 桌面端请求头 status→claim 两步签到）；
     * "teleagent" = 星辰超级智能体 TeleAgent 专用流程（X-Token 鉴权 + 每日任务→领取 + 福利社活动批量领取）。
     */
    public String kind = "";

    // ---------------- 凭据自动续期 ----------------
    /** 当前凭据值（token / Cookie 串），签到请求里用 {{token}} 引用。 */
    public String credValue = "";
    /** 续期接口：拿新凭据的地址，留空表示不启用自动续期。 */
    public String renewUrl = "";
    public String renewMethod = "POST";
    /** 续期请求头；留空沿用签到请求头。 */
    public String renewHeaders = "";
    public String renewBody = "";
    /** 从续期响应里取新凭据的路径：$.data.accessToken 或 @set-cookie。 */
    public String renewExtract = "";
    /** 主动续期周期（小时）；0 表示仅在签到失败（401/403）时才续期。 */
    public int renewEveryHours = 0;
    /** 上次续期成功时间戳，0 表示从未续期。 */
    public long lastRenewAt = 0L;

    // ---------------- 凭据有效期 ----------------
    /** 凭据有效期（小时），0 表示不跟踪；配合 credIssuedAt 推算到期时间。 */
    public int credValidHours = 0;
    /** 当前凭据的填入/刷新时间戳，0 表示未知。 */
    public long credIssuedAt = 0L;
    /** 上次到期提醒时间戳（冷却用），0 表示从未提醒。 */
    public long lastWarnAt = 0L;

    // ---------------- MiniMax 专用令牌 ----------------
    /** MiniMax 的 refresh token：长期有效，换 access token 时会被上游轮换，必须回写。 */
    public String credRefresh = "";
    /** MiniMax access token / Trae 登录态的到期时间戳（毫秒），0 表示未知。 */
    public long credExpireAt = 0L;

    // ---------------- Trae 专用 ----------------
    /** Trae 的 x-device-id：从 storage.json 的 iCubeAuthInfo://icube-dc: 键解析，claim 时必须携带。 */
    public String credDeviceId = "";

    // ---------------- TeleAgent 专用 ----------------
    /** TeleAgent 的设备号：本机生成稳定 UUID 即可，随请求头 X-SuperAgent-Device-Id 上报。 */
    // 复用 credDeviceId 字段，不再新增列。

    public Platform() {
    }

    public static Platform create(String name, String url) {
        Platform p = new Platform();
        p.id = UUID.randomUUID().toString();
        p.name = name;
        p.url = url;
        return p;
    }

    public String initial() {
        if (name == null || name.trim().length() == 0) {
            return "A";
        }
        return name.trim().substring(0, 1).toUpperCase();
    }

    /** 是否配置了续期接口。 */
    public boolean hasRenew() {
        return renewUrl != null && renewUrl.trim().length() > 0
                && renewExtract != null && renewExtract.trim().length() > 0;
    }

    /** 是否走 MiniMax 专用流程。 */
    public boolean isMinimax() {
        return "minimax".equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 是否已经授权过 MiniMax（有 access token 或有可用的 refresh token）。 */
    public boolean hasMinimaxToken() {
        return isMinimax() && ((credValue != null && credValue.trim().length() > 0)
                || (credRefresh != null && credRefresh.trim().length() > 0));
    }

    /** 是否走 Trae CN 专用流程。 */
    public boolean isTrae() {
        return "trae".equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 是否走星辰超级智能体 TeleAgent 专用流程。 */
    public boolean isTeleagent() {
        return "teleagent".equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 是否已导入 TeleAgent 登录令牌（X-Token / operationToken）。 */
    public boolean hasTeleagentToken() {
        if (!isTeleagent()) {
            return false;
        }
        String v = credValue == null ? "" : credValue.trim();
        return v.length() > 0 && !v.toLowerCase().contains("填你的")
                && !v.contains("在此粘贴") && !v.contains("请粘贴");
    }

    /** 是否已经导入 Trae 登录态。 */
    public boolean hasTraeToken() {
        return isTrae() && credValue != null && credValue.trim().length() > 0;
    }

    /** 是否走百度搭子专用流程。 */
    public boolean isDumate() {
        return "dumate".equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 是否已配置百度搭子 Cookie：优先看「当前凭据」，其次看请求头里的 Cookie 行。 */
    public boolean hasDumateCookie() {
        if (!isDumate()) {
            return false;
        }
        String cred = credValue == null ? "" : credValue.trim();
        if (cred.startsWith("Cookie:") || cred.startsWith("cookie:")) {
            cred = cred.substring(7).trim();
        }
        if (usableCookie(cred)) {
            return true;
        }
        String h = this.headers == null ? "" : this.headers;
        String[] lines = h.split("\n");
        for (String line : lines) {
            String s = line.trim();
            int idx = s.indexOf(':');
            if (idx <= 0 || !"Cookie".equalsIgnoreCase(s.substring(0, idx).trim())) {
                continue;
            }
            if (usableCookie(s.substring(idx + 1).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean usableCookie(String value) {
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

    /** 到点该主动续期了吗（周期为 0 时永远返回 false，只在失败时被动续期）。 */
    public boolean renewDue(long now) {
        if (!hasRenew() || renewEveryHours <= 0) {
            return false;
        }
        if (lastRenewAt <= 0) {
            return true;
        }
        return now - lastRenewAt >= renewEveryHours * 3600L * 1000L;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("preUrl", preUrl);
            o.put("method", method);
            o.put("headers", headers);
            o.put("body", body);
            o.put("keyword", keyword);
            o.put("doneKeyword", doneKeyword);
            o.put("enabled", enabled);
            o.put("credValue", credValue);
            o.put("renewUrl", renewUrl);
            o.put("renewMethod", renewMethod);
            o.put("renewHeaders", renewHeaders);
            o.put("renewBody", renewBody);
            o.put("renewExtract", renewExtract);
            o.put("renewEveryHours", renewEveryHours);
            o.put("lastRenewAt", lastRenewAt);
            o.put("credValidHours", credValidHours);
            o.put("credIssuedAt", credIssuedAt);
            o.put("lastWarnAt", lastWarnAt);
            o.put("kind", kind);
            o.put("credRefresh", credRefresh);
            o.put("credExpireAt", credExpireAt);
            o.put("credDeviceId", credDeviceId);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Platform fromJson(JSONObject o) {
        Platform p = new Platform();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "未命名");
        p.url = o.optString("url", "");
        p.method = o.optString("method", "GET");
        p.headers = o.optString("headers", "");
        p.body = o.optString("body", "");
        p.keyword = o.optString("keyword", "");
        p.doneKeyword = o.optString("doneKeyword", "");
        p.preUrl = o.optString("preUrl", "");
        p.enabled = o.optBoolean("enabled", true);
        p.credValue = o.optString("credValue", "");
        p.renewUrl = o.optString("renewUrl", "");
        p.renewMethod = o.optString("renewMethod", "POST");
        p.renewHeaders = o.optString("renewHeaders", "");
        p.renewBody = o.optString("renewBody", "");
        p.renewExtract = o.optString("renewExtract", "");
        p.renewEveryHours = o.optInt("renewEveryHours", 0);
        p.lastRenewAt = o.optLong("lastRenewAt", 0L);
        p.credValidHours = o.optInt("credValidHours", 0);
        p.credIssuedAt = o.optLong("credIssuedAt", 0L);
        p.lastWarnAt = o.optLong("lastWarnAt", 0L);
        p.kind = o.optString("kind", "");
        p.credRefresh = o.optString("credRefresh", "");
        p.credExpireAt = o.optLong("credExpireAt", 0L);
        p.credDeviceId = o.optString("credDeviceId", "");
        return p;
    }
}
