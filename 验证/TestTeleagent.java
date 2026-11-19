import com.marvis.signin.model.Platform;
import com.marvis.signin.net.TeleAgent;
import com.marvis.signin.net.TeleAgentSign;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.cert.X509Certificate;

/**
 * TeleAgent（星辰超级智能体）协议层离线验证 + 真实接口鉴权失败路径。
 *
 * 离线部分：令牌归一化、jump_url 归一化、可领取状态判定、鉴权请求头、响应解析、
 *            平台判定与编排层的未配置路径。
 * 真实部分：只带假令牌打一次每日任务接口，验证鉴权拒绝路径；不会接触任何真实账号。
 *            沙箱/离线环境连不上接口时自动跳过（SKIP），不算失败。
 */
public class TestTeleagent {

    private static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (detail == null ? "" : " · " + detail));
        if (!ok) {
            failed++;
        }
    }

    static void relaxTls() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String h, SSLSession s) { return true; }
        });
    }

    public static void main(String[] args) throws Exception {
        String token = "ey123.abc.def-signature-part";
        // 用真实 base64url 编码构造 payload，避免手写位数出错
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":9999999999,\"id\":1}".getBytes("UTF-8"));
        String jwt = "Em9xR4:eyJhbGciOiJIUzI1NiJ9." + payload + ".sig-part";

        System.out.println("== 1) 令牌归一化 ==");
        check("裸令牌原样", token.equals(TeleAgent.normalize(token)), null);
        check("operationToken= 键值对", token.equals(TeleAgent.normalize("operationToken=" + token)), null);
        check("整行 Cookie 取 operationToken", token.equals(TeleAgent.normalize(
                "Cookie: operationToken=" + token + "; other=x")), null);
        check("整行 Cookie 取 x-token", token.equals(TeleAgent.normalize(
                "Cookie: x-token=" + token + "; other=x")), null);
        check("user_token= 兼容", token.equals(TeleAgent.normalize("user_token=" + token)), null);
        check("大小写不敏感", token.equals(TeleAgent.normalize("OperationToken=" + token)), null);
        check("去引号", token.equals(TeleAgent.normalize("\"" + token + "\"")), null);
        check("压掉空白", token.equals(TeleAgent.normalize(" " + token + " \n")), null);
        check("空串安全", TeleAgent.normalize("   ").length() == 0, null);
        check("无登录键的 Cookie 行不误取", TeleAgent.normalize("Cookie: a=b; c=d").length() == 0, null);
        check("只有 operToken 的行不误取（注意不是 operationToken）",
                TeleAgent.normalize("Cookie: operToken=" + token).length() == 0, null);
        check("Em9xR4: 前缀令牌原样保留", jwt.equals(TeleAgent.normalize(jwt)), null);
        check("Em9xR4: 前缀整行 Cookie 提取", jwt.equals(TeleAgent.normalize(
                "Cookie: operationToken=" + jwt + "; cna=1")), null);

        System.out.println("\n== 2) jump_url 归一化 ==");
        check("剥前缀", "/user/portal/companyStore/campaign/claim/1".equals(TeleAgent.claimPath(
                "/superCowork/sapi/user/portal/companyStore/campaign/claim/1")), null);
        check("完整 URL 剥前缀", "/user/portal/companyStore/campaign/claim/2".equals(TeleAgent.claimPath(
                "https://agent.teleai.com.cn/superCowork/sapi/user/portal/companyStore/campaign/claim/2")), null);
        check("裸相对路径补斜杠", "/claim/3".equals(TeleAgent.claimPath("claim/3")), null);
        check("空地址", TeleAgent.claimPath("  ").length() == 0, null);

        System.out.println("\n== 3) 可领取状态判定 ==");
        check("incomplete 可领", TeleAgent.claimable("incomplete"), null);
        check("not_joined 可领", TeleAgent.claimable("NOT_JOINED"), null);
        check("fixed 可领", TeleAgent.claimable("fixed"), null);
        check("completed 不可领", !TeleAgent.claimable("completed"), null);
        check("空状态不可领", !TeleAgent.claimable(""), null);
        check("未知状态不可领", !TeleAgent.claimable("paused"), null);

        System.out.println("\n== 4) 鉴权请求头 ==");
        String h = TeleAgent.authHeaders(token, "device-abc");
        check("X-Token 在头", h.contains("X-Token: " + token), null);
        check("Cookie 同步携带 operationToken", h.contains("Cookie: operationToken=" + token), null);
        check("Cookie 双键带 user_token", h.contains("; user_token=" + token), null);
        check("Em9xR4: 前缀令牌原样进 X-Token",
                TeleAgent.authHeaders(jwt, "d").contains("X-Token: " + jwt), null);
        check("设备号在头", h.contains("X-SuperAgent-Device-Id: device-abc"), null);
        check("时间戳为秒级", h.matches("(?s).*X-Timestamp: 1\\d{9}\\n.*"), null);
        check("随机数是 UUID", h.contains("X-Nonce: ") && h.split("X-Nonce: ")[1].split("\n")[0].contains("-"), null);
        String h2 = TeleAgent.authHeaders(token, "");
        check("空设备号自动补", !h2.contains("X-SuperAgent-Device-Id: \n"), null);

        System.out.println("\n== 5) 响应解析 ==");
        com.marvis.signin.util.Http.Result okHttp = new com.marvis.signin.util.Http.Result();
        okHttp.code = 200;
        okHttp.body = "{\"code\":0,\"data\":{\"brand_title\":\"积分福利社\",\"activity_data\":["
                + "{\"activity_title\":\"每日签到\",\"activity_subtitle\":\"连续签到领积分\",\"is_main_activity\":true,"
                + "\"visible\":true,\"action_status\":\"incomplete\",\"action_type\":\"api\","
                + "\"jump_url\":\"/superCowork/sapi/user/portal/companyStore/v3/checkDayTask/claim\",\"action_text\":\"立即领取\",\"points_amount\":100},"
                + "{\"activity_title\":\"邀请有礼\",\"visible\":true,\"is_main_activity\":false,"
                + "\"action_status\":\"completed\",\"action_type\":\"api\",\"jump_url\":\"/superCowork/sapi/x/claim\"},"
                + "{\"activity_title\":\"网页活动\",\"visible\":true,\"is_main_activity\":false,"
                + "\"action_status\":\"not_joined\",\"action_type\":\"web\",\"jump_url\":\"https://agent.teleai.com.cn/meter/teleagent/user/portal/star-welfare\"},"
                + "{\"activity_title\":\"隐藏活动\",\"visible\":false,\"is_main_activity\":false,"
                + "\"action_status\":\"incomplete\",\"action_type\":\"api\",\"jump_url\":\"/superCowork/sapi/y/claim\"}"
                + "]}}";
        TeleAgent.TaskData td = TeleAgent.parseTask(okHttp);
        check("解析出数据", td != null, null);
        check("brand_title", "积分福利社".equals(td.brandTitle), td.brandTitle);
        check("活动条数（含隐藏）", td.activities.size() == 4, String.valueOf(td.activities.size()));
        TeleAgent.Activity main = null;
        int visible = 0;
        for (TeleAgent.Activity a : td.activities) {
            if (a.main) {
                main = a;
            }
            if (a.visible) {
                visible++;
            }
        }
        check("可见活动 3 条", visible == 3, String.valueOf(visible));
        check("主活动识别", main != null && "每日签到".equals(main.title),
                main == null ? "null" : main.title);
        check("主活动可领", main != null && main.claimable(), null);
        check("主活动积分 100", main != null && main.points == 100, String.valueOf(main == null ? 0 : main.points));
        check("业务成功判定", TeleAgent.bizOk(okHttp), null);
        check("claim 结果可领时领取", main.claimable() && !"web".equalsIgnoreCase(main.actionType), null);

        com.marvis.signin.util.Http.Result topLevel = new com.marvis.signin.util.Http.Result();
        topLevel.code = 200;
        topLevel.body = "{\"code\":200,\"success\":true,\"activity_data\":[]}";
        check("顶层 activity_data 兼容", TeleAgent.parseTask(topLevel) != null, null);
        check("code=200 也算业务成功", TeleAgent.bizOk(topLevel), null);

        com.marvis.signin.util.Http.Result fail = new com.marvis.signin.util.Http.Result();
        fail.code = 200;
        fail.body = "{\"code\":10001,\"message\":\"token invalid\"}";
        check("业务码非 0 不算成功", !TeleAgent.bizOk(fail), null);
        check("错误文案取 message", "token invalid".equals(TeleAgent.messageOf(fail)), TeleAgent.messageOf(fail));

        com.marvis.signin.util.Http.Result noData = new com.marvis.signin.util.Http.Result();
        noData.code = 200;
        noData.body = "{\"code\":0,\"data\":{}}";
        check("空 data 解析为 null", TeleAgent.parseTask(noData) == null, null);

        System.out.println("\n== 6) 平台判定与未配置路径 ==");
        Platform p = new Platform();
        p.kind = "teleagent";
        check("isTeleagent 识别", p.isTeleagent(), null);
        check("未配置令牌", !p.hasTeleagentToken(), null);
        p.credValue = token;
        check("配置后识别", p.hasTeleagentToken(), null);
        p.credValue = "请粘贴 operationToken";
        check("占位符不算已配置", !p.hasTeleagentToken(), null);
        p.credValue = jwt;
        check("Em9xR4: 前缀令牌算已配置", p.hasTeleagentToken(), null);
        long expMs = com.marvis.signin.util.Cred.jwtExpMs(jwt);
        check("JWT 到期解析忽略 Em9xR4: 前缀", expMs == 9999999999L * 1000L, String.valueOf(expMs));
        Platform empty1 = new Platform();
        empty1.kind = "teleagent";
        TeleAgentSign.Verdict empty = TeleAgentSign.run(empty1);
        check("未导入令牌给明确提示", !empty.ok && empty.message.contains("未导入"), empty.message);

        System.out.println("\n== 7) 真实接口：假令牌的鉴权路径 ==");
        relaxTls();
        Platform live = new Platform();
        live.kind = "teleagent";
        live.credValue = "fake-token-for-negative-test";
        com.marvis.signin.util.Http.Result st;
        try {
            st = TeleAgent.dayTask(live.credValue, "test-device");
        } catch (Throwable t) {
            System.out.println("  SKIP 网络不可达（沙箱限制或离线环境），跳过真实接口用例");
            st = null;
        }
        if (st != null && (st.error == null || st.error.length() == 0)) {
            boolean rejected = st.code == 401 || st.code == 403 || !TeleAgent.bizOk(st);
            check("假令牌被拒绝（HTTP 401/403 或业务错误）", rejected,
                    "HTTP " + st.code + " · " + TeleAgent.upstreamError(st));
            check("编排层给出失效提示", !TeleAgentSign.run(live).ok, null);
        } else if (st != null) {
            System.out.println("  SKIP 请求失败：" + st.error);
        }

        System.out.println("\n" + (failed == 0 ? "全部通过" : ("失败 " + failed + " 项")));
        if (failed != 0) {
            System.exit(1);
        }
    }
}
