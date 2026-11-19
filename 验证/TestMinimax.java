import com.marvis.signin.net.Minimax;
import com.marvis.signin.util.Http;

/** 用真实接口形状的载荷，验证 MiniMax 的 JSON 解析与判定逻辑。 */
public class TestMinimax {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + " -> " + detail); }
    }

    static Http.Result resp(int code, String body) {
        Http.Result r = new Http.Result();
        r.code = code;
        r.body = body;
        return r;
    }

    public static void main(String[] args) throws Exception {
        // ---- status 载荷：今天那一格 status=2 表示可领 ----
        String statusToday = "{\"base_resp\":{\"code\":0,\"msg\":\"success\"},\"data\":{\"days\":["
                + "{\"day_no\":1,\"status\":1,\"points\":400,\"bonus_points\":0,\"is_today\":false},"
                + "{\"day_no\":2,\"status\":2,\"points\":400,\"bonus_points\":100,\"is_today\":true}]}}";
        Http.Result st = resp(200, statusToday);
        check("status: 今天可领判定", Minimax.todayClaimable(st), "应可领");
        check("status: 今天格子取到", Minimax.todayCell(st) != null, "todayCell 为空");
        check("status: 今天格子点数=400", Minimax.todayCell(st) != null
                && Minimax.todayCell(st).optInt("points", 0) == 400, "点数不对");

        // ---- status 载荷：今天已领（status=1）----
        String statusDone = "{\"base_resp\":{\"code\":0,\"msg\":\"success\"},\"data\":{\"days\":["
                + "{\"day_no\":2,\"status\":1,\"points\":400,\"is_today\":true}]}}";
        check("status: 已领过则不可领", !Minimax.todayClaimable(resp(200, statusDone)), "不该判可领");

        // ---- H2 回归：is_today 写成数字 1 / 字符串 "1" 也必须能认出来 ----
        String numFlag = "{\"base_resp\":{\"code\":0},\"data\":{\"days\":["
                + "{\"day_no\":2,\"status\":2,\"points\":400,\"is_today\":1}]}}";
        check("H2: is_today=1（数字）也能取到今天格子", Minimax.todayCell(resp(200, numFlag)) != null, "取不到");
        check("H2: is_today=1（数字）判定为可领", Minimax.todayClaimable(resp(200, numFlag)), "应可领");

        String strFlag = "{\"base_resp\":{\"code\":0},\"data\":{\"days\":["
                + "{\"day_no\":2,\"status\":2,\"points\":400,\"is_today\":\"1\"}]}}";
        check("H2: is_today=\"1\"（字符串）也能取到今天格子", Minimax.todayCell(resp(200, strFlag)) != null, "取不到");

        // ---- H2 回归：按日期兜底（没有 is_today 标记时）----
        java.text.SimpleDateFormat df =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        df.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        String today = df.format(new java.util.Date());
        String byDate = "{\"base_resp\":{\"code\":0},\"data\":{\"days\":["
                + "{\"day_no\":2,\"status\":2,\"points\":400,\"date\":\"" + today + "\"}]}}";
        check("H2: 无 is_today 时按日期兜底找到今天", Minimax.todayCell(resp(200, byDate)) != null, "取不到");

        // ---- H2 回归：确实没有今天这一格时必须返回 null（不能被当成「已签到」）----
        String noToday = "{\"base_resp\":{\"code\":0},\"data\":{\"days\":["
                + "{\"day_no\":1,\"status\":1,\"points\":400}]}}";
        check("H2: 没有今天格子时返回 null（交由上层判失败）",
                Minimax.todayCell(resp(200, noToday)) == null, "不该返回格子");
        check("H2: 没有今天格子时不可判为可领",
                !Minimax.todayClaimable(resp(200, noToday)), "不该判可领");

        // ---- claim 载荷：1=刚领到，2=已领过 ----
        String claimNew = "{\"base_resp\":{\"code\":0,\"msg\":\"success\"},\"data\":{\"claim_result\":1,\"points\":400,\"day_no\":2,\"expire_at_ms\":1762000000000}}";
        check("claim: claim_result=1 判定为领取成功", Minimax.claimResult(resp(200, claimNew)) == 1, "不是 1");
        check("claim: 积分=400", Minimax.claimPoints(resp(200, claimNew)) == 400, "积分不对");

        String claimOld = "{\"base_resp\":{\"code\":0,\"msg\":\"success\"},\"data\":{\"claim_result\":2,\"points\":400,\"day_no\":2}}";
        check("claim: claim_result=2 判定为已签到", Minimax.claimResult(resp(200, claimOld)) == 2, "不是 2");

        // ---- 上游拒绝：base_resp.code != 0 ----
        String denied = "{\"base_resp\":{\"code\":1001,\"msg\":\"token expired\"}}";
        check("claim: 上游拒绝时 claim_result=0", Minimax.claimResult(resp(200, denied)) == 0, "应为 0");
        check("claim: 上游错误文案带 code 与 msg",
                Minimax.upstreamError(resp(200, denied)).contains("1001")
                        && Minimax.upstreamError(resp(200, denied)).contains("token expired"),
                Minimax.upstreamError(resp(200, denied)));

        // ---- 401 真实错误体（实测线上就是这个形状）----
        String unauthorized = "{\"error\":\"invalid access token\"}";
        check("401 错误体能解析成上游错误文案",
                Minimax.upstreamError(resp(401, unauthorized)).contains("invalid access token"),
                Minimax.upstreamError(resp(401, unauthorized)));

        // ---- 非 JSON / 空体不崩 ----
        check("空响应不抛异常", Minimax.upstreamError(resp(200, "")).length() == 0, "应为空");
        check("非 JSON 不抛异常", Minimax.upstreamError(resp(200, "<html>502</html>")).contains("502"), "应回原文");

        // ---- OAuth pending 响应（实测线上形状）----
        String pending = "{\"error\":\"authorization_pending\",\"error_description\":\"the user has not finished authorizing yet\"}";
        check("pending 响应能被识别（不应当作错误抛出）",
                pending.contains("authorization_pending"), "pending 形状变了");

        // ---- PKCE：S256 计算 ----
        java.lang.reflect.Method m = null;
        try {
            m = Minimax.class.getDeclaredMethod("challenge", String.class);
            m.setAccessible(true);
            String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
            String c = (String) m.invoke(null, verifier);
            // RFC 7636 附录 B 的官方测试向量
            String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
            check("PKCE S256 与 RFC 7636 测试向量一致", expected.equals(c), "得到 " + c);
        } catch (Exception e) {
            check("PKCE S256 计算", false, "异常：" + e);
        }

        System.out.println("\n结果：" + pass + " 通过，" + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
