package com.marvis.signin.net;

import com.marvis.signin.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 星辰超级智能体 TeleAgent（中国电信）协议层。
 *
 * 积分福利社是桌面端专属功能（手机端暂不支持签到），接口与桌面客户端一致：
 *   服务地址  https://agent.teleai.com.cn/superCowork/sapi
 *   用户中心  GET /user/portal/companyStore/userCenter            （积分余额 / 活动入口）
 *   每日任务  GET /user/portal/companyStore/v3/checkDayTask?theme=light
 *             返回 activity_data[]，主活动即「每日签到」；
 *             其余活动为福利社的可领取活动。
 *   领取      GET <jump_url 去掉 /superCowork/sapi 前缀>
 *             （客户端把 jump_url 的 /superCowork/sapi 前缀去掉后，拼回 BASE_URL 直接 GET）
 *
 * 鉴权（与桌面客户端同一组请求头）：
 *   X-Token                 登录令牌（即福利社网页 Cookie 里的 operationToken）
 *   X-Timestamp             Unix 秒级时间戳
 *   X-Nonce                 随机 UUID
 *   X-App-Version           客户端版本号
 *   X-OS-Type               操作系统标识
 *   X-SuperAgent-Device-Id  设备号（本机生成稳定 UUID 即可）
 *   X-Channel-Id / X-Runtime-Env  渠道与运行环境（可空）
 * 同时把令牌放进 Cookie operationToken=... 与 user_token=...：
 * 网页端鉴权头读的是 operationToken，桌面客户端分区里写的是 user_token，
 * 服务端以 X-Token 头为准，两个 Cookie 键同值都带上，两边登录态均同构。
 *
 * 活动的 action_status 判定（与客户端一致）：
 *   incomplete / not_joined / fixed = 可领取；
 *   completed = 今天已完成；
 *   其它值按不可领取处理。
 * action_type = "web" 的活动需要浏览器会话码跳转，纯 HTTP 领不了，只提示不尝试。
 */
public class TeleAgent {

    public static final String API_BASE = "https://agent.teleai.com.cn/superCowork/sapi";
    public static final String PATH_USER_CENTER = "/user/portal/companyStore/userCenter";
    public static final String PATH_DAY_TASK = "/user/portal/companyStore/v3/checkDayTask";
    /** 福利社网页的登录态 Cookie 键名（网页端 _T() 读取的正是它）。 */
    public static final String COOKIE_NAME = "operationToken";
    /** 等价键：与 operationToken 同值；桌面客户端分区 Cookie 用的则是 user_token。 */
    private static final String[] TOKEN_KEYS = {"operationToken", "x-token", "user_token"};
    /** jump_url 的服务前缀：领取时要去掉，再拼回 API_BASE。 */
    public static final String SAPI_PREFIX = "/superCowork/sapi";

    /** 客户端元信息：与桌面客户端请求头保持同构（仅用于遥测标识，鉴权只认 X-Token）。 */
    public static final String APP_VERSION = "3.1.0";
    public static final String OS_TYPE = "windows";

    /** 客户端认可的「可领取」状态集合。 */
    private static final String[] CLAIMABLE_STATUS = {"incomplete", "not_joined", "fixed"};

    // ---------------- 数据模型 ----------------

    /** 解析出来的福利社每日任务数据。 */
    public static class TaskData {
        public String brandTitle = "";
        public List<Activity> activities = new ArrayList<Activity>();
    }

    /** 一条福利活动（含主活动 = 每日签到）。 */
    public static class Activity {
        public String title = "";
        public String subtitle = "";
        /** incomplete / not_joined / fixed = 可领；completed = 已完成。 */
        public String actionStatus = "";
        /** "web" = 需浏览器会话，无法纯 HTTP 领取。 */
        public String actionType = "";
        public String jumpUrl = "";
        public String actionText = "";
        public boolean main = false;
        public boolean visible = true;
        /** 活动奖励积分（响应里给得出才填，0 表示未知）。 */
        public int points = 0;

        public boolean claimable() {
            return TeleAgent.claimable(actionStatus);
        }
    }

    // ---------------- 请求 ----------------

    /** 查用户积分中心（带积分余额与活动入口，兼作登录态校验）。 */
    public static Http.Result userCenter(String token, String deviceId) {
        return Http.execute(API_BASE + PATH_USER_CENTER, "GET",
                authHeaders(token, deviceId), null, 20000, null);
    }

    /** 查福利社每日任务（签到状态 + 可领取活动列表）。 */
    public static Http.Result dayTask(String token, String deviceId) {
        return Http.execute(API_BASE + PATH_DAY_TASK + "?theme=light", "GET",
                authHeaders(token, deviceId), null, 20000, null);
    }

    /**
     * 领取一条活动：jump_url 去掉 /superCowork/sapi 前缀后拼回 API_BASE。
     * 与客户端的领取逻辑逐字对应：jump_url.replace("/superCowork/sapi", "")。
     */
    public static Http.Result claim(String token, String deviceId, String jumpUrl) {
        return Http.execute(API_BASE + claimPath(jumpUrl), "GET",
                authHeaders(token, deviceId), null, 20000, null);
    }

    /** 把客户端的 jump_url 归一化成 API_BASE 之后的相对路径。 */
    public static String claimPath(String jumpUrl) {
        String u = jumpUrl == null ? "" : jumpUrl.trim();
        if (u.length() == 0) {
            return "";
        }
        // 完整 URL：截取 /superCowork/sapi 之后的部分
        int idx = u.indexOf(SAPI_PREFIX);
        if (u.startsWith("http://") || u.startsWith("https://")) {
            if (idx >= 0) {
                return u.substring(idx + SAPI_PREFIX.length());
            }
            return u; // 其它域名的完整地址，交给 Http 直接发
        }
        // 相对路径：剥掉前缀（客户端就是简单 replace，这里更严谨一点）
        if (u.startsWith(SAPI_PREFIX)) {
            u = u.substring(SAPI_PREFIX.length());
        } else if (idx >= 0) {
            u = u.substring(idx + SAPI_PREFIX.length());
        }
        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        return u;
    }

    /** 把 jump_url 归一化成完整活动页地址（web 型活动的落地页，给应用内浏览器加载）。 */
    public static String pageUrl(String jumpUrl) {
        String u = jumpUrl == null ? "" : jumpUrl.trim();
        if (u.length() == 0) {
            return "";
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        return "https://agent.teleai.com.cn" + u;
    }

    /** 鉴权请求头：与桌面客户端 O6() 生成的同一组头。 */
    public static String authHeaders(String token, String deviceId) {
        String t = token == null ? "" : token.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("X-Token: ").append(t).append('\n');
        sb.append("X-Timestamp: ").append(System.currentTimeMillis() / 1000L).append('\n');
        sb.append("X-Nonce: ").append(UUID.randomUUID().toString()).append('\n');
        sb.append("X-App-Version: ").append(APP_VERSION).append('\n');
        sb.append("X-OS-Type: ").append(OS_TYPE).append('\n');
        sb.append("X-SuperAgent-Device-Id: ")
                .append(deviceId == null || deviceId.trim().length() == 0 ? stableDeviceId() : deviceId.trim())
                .append('\n');
        // 网页端登录态键 operationToken + 桌面客户端分区键 user_token，同值双发，两边登录态同构
        sb.append("Cookie: ").append(COOKIE_NAME).append('=').append(t)
                .append("; user_token=").append(t).append('\n');
        sb.append("Accept: application/json, text/plain, */*");
        return sb.toString();
    }

    /** 本机稳定的设备号（无持久存储时由调用方保存到 credDeviceId，这里兜底随机）。 */
    public static String stableDeviceId() {
        return UUID.randomUUID().toString();
    }

    // ---------------- 粘贴内容归一化 ----------------

    /**
     * 归一化用户粘贴的令牌。兼容这些写法：
     *   1) 裸令牌（推荐：devtools 里 operationToken 的 Value 列）；
     *   2) operationToken=xxx / x-token=xxx / user_token=xxx 键值对；
     *   3) Cookie: operationToken=xxx; 其它键=... 整行 Cookie；
     *   4) 带引号的值。
     *
     * 注意：operToken（teleai.com.cn 主域通行证）不是签到令牌，
     * 整行 Cookie 里只有它时宁可返回空串，让用户得到明确报错。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return "";
        }
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1).trim();
        }
        String lower = s.toLowerCase();
        String picked = null;
        for (String key : TOKEN_KEYS) {
            int idx = lower.indexOf(key.toLowerCase() + "=");
            if (idx >= 0) {
                String val = s.substring(idx + key.length() + 1);
                int end = val.indexOf(';');
                if (end >= 0) {
                    val = val.substring(0, end);
                }
                val = val.trim();
                if (val.length() > 0) {
                    picked = val;
                    break;
                }
            }
        }
        if (picked != null) {
            s = picked;
        } else if (lower.startsWith("cookie:")) {
            // 整行 Cookie 里没有三个已知键：令牌不在这段文本里，返回空串给明确报错
            return "";
        }
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // Cookie 抓包常见的多行拆断：合并同一行内的空白（令牌本身不含空白）
        return s.replaceAll("\\s+", "");
    }

    // ---------------- 响应解析 ----------------

    /**
     * 取业务数据对象。网关返回形如 { code: 0, data: {...} }；
     * 个别接口会把数据直接放在顶层，做个兼容：顶层有 activity_data 时也按数据层处理。
     */
    public static JSONObject dataOf(Http.Result r) {
        JSONObject root = root(r);
        if (root == null) {
            return null;
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null && (data.has("activity_data") || data.has("brand_title")
                || data.has("max_concurrent_tasks") || data.has("all_activities_url"))) {
            return data;
        }
        if (root.has("activity_data")) {
            return root;
        }
        return data; // 没有 data 字段时原样返回（可能就是裸数据对象）
    }

    public static JSONObject root(Http.Result r) {
        if (r == null || r.body == null || r.body.trim().length() == 0) {
            return null;
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 业务码：网关约定 0 / 200 / success=true 都算成功（与客户端判定一致）。 */
    public static boolean bizOk(Http.Result r) {
        JSONObject o = root(r);
        if (o == null) {
            return false;
        }
        int code = o.optInt("code", -1);
        if (code == 0 || code == 200) {
            return true;
        }
        return o.optBoolean("success", false);
    }

    public static int bizCode(Http.Result r) {
        JSONObject o = root(r);
        return o == null ? -1 : o.optInt("code", -1);
    }

    public static String messageOf(Http.Result r) {
        JSONObject o = root(r);
        if (o == null) {
            return "";
        }
        String m = o.optString("message", "");
        if (m.length() == 0) {
            m = o.optString("msg", "");
        }
        if (m.length() == 0) {
            m = o.optString("resultMsg", "");
        }
        return m;
    }

    /** 解析每日任务响应；结构对不上返回 null。 */
    public static TaskData parseTask(Http.Result r) {
        JSONObject data = dataOf(r);
        if (data == null) {
            return null;
        }
        TaskData td = new TaskData();
        td.brandTitle = data.optString("brand_title", "");
        JSONArray arr = data.optJSONArray("activity_data");
        if (arr == null) {
            return null;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Activity a = new Activity();
            a.title = o.optString("activity_title", "");
            a.subtitle = o.optString("activity_subtitle", "");
            a.actionStatus = o.optString("action_status", "");
            a.actionType = o.optString("action_type", "");
            a.jumpUrl = o.optString("jump_url", "");
            a.actionText = o.optString("action_text", "");
            a.main = o.optBoolean("is_main_activity", false);
            a.visible = o.optBoolean("visible", true);
            a.points = pointsOf(o);
            td.activities.add(a);
        }
        return td;
    }

    /** 活动奖励积分：字段名各版本可能不同，逐个试探。 */
    private static int pointsOf(JSONObject o) {
        String[] keys = {"points_amount", "reward_points", "points", "point", "reward"};
        for (String k : keys) {
            int v = o.optInt(k, 0);
            if (v > 0) {
                return v;
            }
        }
        return 0;
    }

    /** 用户中心的积分余额（拿得到就展示，拿不到返回 0）。 */
    public static int pointsBalance(Http.Result r) {
        JSONObject data = dataOf(r);
        if (data == null) {
            return 0;
        }
        String[] keys = {"point", "points", "total_point", "totalPoint",
                "available_point", "availablePoint", "balance", "star"};
        for (String k : keys) {
            int v = data.optInt(k, 0);
            if (v > 0) {
                return v;
            }
        }
        return 0;
    }

    /** 该状态是否可领取（与客户端 Wte() 一致）。 */
    public static boolean claimable(String actionStatus) {
        String s = actionStatus == null ? "" : actionStatus.trim().toLowerCase();
        for (String one : CLAIMABLE_STATUS) {
            if (one.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** 上游错误原因（优先 message，其次原始响应片段）。 */
    public static String upstreamError(Http.Result r) {
        String msg = messageOf(r);
        if (msg.length() > 0) {
            int c = bizCode(r);
            return (c >= 0 ? "code " + c + "：" : "") + msg;
        }
        return cut(r == null ? "" : r.body, 120);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
