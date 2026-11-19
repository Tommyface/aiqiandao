import com.marvis.signin.model.Platform;
import com.marvis.signin.net.Dumate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.cert.X509Certificate;

/**
 * 百度搭子协议层离线验证 + 真实接口鉴权失败路径。
 *
 * 编译方式见 验证/README.txt。真实请求只带假 Cookie，不会碰到任何真实账号。
 */
public class TestDumate {

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
        String cookie = "BDUSS=abc123; STOKEN=xyz; bce-user-info=%7B%22token%22%3A%22csrf-1%22%7D";

        System.out.println("== 1) Cookie 解析 ==");
        Platform p = new Platform();
        p.url = "https://console.bce.baidu.com/api/dumate/points/loginBonus";
        p.credValue = cookie;
        check("credValue 直接识别", cookie.equals(Dumate.cookieOf(p)), null);
        Platform h = new Platform();
        h.headers = "Cookie: " + cookie + "\nX-Dumate-Client-Type: desktop";
        check("从请求头 Cookie 行识别", cookie.equals(Dumate.cookieOf(h)), null);
        Platform empty = new Platform();
        empty.headers = "Cookie: 填你的Cookie（需含 bce-user-info）";
        check("占位符不当成 Cookie", Dumate.cookieOf(empty).length() == 0, null);

        System.out.println("\n== 2) csrfToken ==");
        check("bce-user-info URL 解码", "{\"token\":\"csrf-1\"}".equals(Dumate.csrfOf(cookie)),
                Dumate.csrfOf(cookie));
        check("缺字段返回空", Dumate.csrfOf("BDUSS=x").length() == 0, null);

        Platform noCsrf = new Platform();
        noCsrf.credValue = "BDUSS=abc123; STOKEN=xyz";
        check("缺 bce-user-info 仍能识别 Cookie", Dumate.cookieOf(noCsrf).length() > 0, null);
        check("缺 bce-user-info 时 csrf 为空", Dumate.csrfOf(noCsrf, Dumate.cookieOf(noCsrf)).length() == 0, null);

        Platform csrfHeader = new Platform();
        csrfHeader.credValue = "BDUSS=abc123";
        csrfHeader.headers = "csrfToken: csrf-from-header";
        check("从请求头 csrfToken 兜底",
                "csrf-from-header".equals(Dumate.csrfOf(csrfHeader, Dumate.cookieOf(csrfHeader))),
                Dumate.csrfOf(csrfHeader, Dumate.cookieOf(csrfHeader)));

        Platform wrapped = new Platform();
        wrapped.credValue = "BDUSS=abc123;\nbce-user-info=%7B%22t%22%3A1%7D";
        check("换行拆断的 Cookie 仍能取 csrf",
                "{\"t\":1}".equals(Dumate.csrfOf(wrapped, Dumate.cookieOf(wrapped))),
                Dumate.csrfOf(wrapped, Dumate.cookieOf(wrapped)));

        System.out.println("\n== 3) 桌面端请求头 ==");
        String headers = Dumate.headerText(cookie, Dumate.csrfOf(cookie), Dumate.DEFAULT_BASE);
        check("带 desktop 标识", headers.contains("X-Dumate-Client-Type: desktop"), null);
        check("带 csrfToken", headers.contains("csrfToken: {\"token\":\"csrf-1\"}"), null);
        check("带 Cookie", headers.contains("Cookie: " + cookie), null);
        check("Referer 指向网页版", headers.contains("Referer: https://dumate.baidu.com/app"), null);

        System.out.println("\n== 4) 域名白名单 ==");
        check("console 域名", "https://console.bce.baidu.com".equals(Dumate.baseOf(p)), Dumate.baseOf(p));
        Platform dm = new Platform();
        dm.url = "https://dumate.baidu.com/api/dumate/points/loginBonus";
        check("dumate 域名", "https://dumate.baidu.com".equals(Dumate.baseOf(dm)), Dumate.baseOf(dm));
        Platform evil = new Platform();
        evil.url = "https://evil.example.com/api/dumate/points/loginBonus";
        check("陌生域名回退默认值", Dumate.DEFAULT_BASE.equals(Dumate.baseOf(evil)), Dumate.baseOf(evil));

        System.out.println("\n== 5) 响应解析 ==");
        com.marvis.signin.util.Http.Result okHttp = new com.marvis.signin.util.Http.Result();
        okHttp.code = 200;
        okHttp.body = "{\"code\":0,\"data\":{\"result\":{\"hasIssued\":false,\"signInDays\":[\"2026-11-12\",\"2026-11-13\"],\"totalPoints\":1500,\"totalTimes\":8,\"issuedToast\":\"\"}}}";
        Dumate.Response st = Dumate.parse(okHttp);
        Dumate.Info info = Dumate.infoOf(st);
        check("code=0 视为成功", st.ok, null);
        check("hasIssued=false", !info.hasIssued, null);
        check("签到天数=2", info.signInDays == 2, String.valueOf(info.signInDays));
        check("总积分=1500", Math.abs(info.totalPoints - 1500) < 0.01, String.valueOf(info.totalPoints));

        com.marvis.signin.util.Http.Result expired = new com.marvis.signin.util.Http.Result();
        expired.code = 401;
        expired.body = "{\"code\":10001,\"message\":\"登录已经过期\"}";
        Dumate.Response bad = Dumate.parse(expired);
        check("401 不通过", !bad.ok, null);
        check("识别登录失效", Dumate.authFailed(bad), null);
        check("错误文案", "登录已经过期".equals(Dumate.errorOf(bad)), Dumate.errorOf(bad));

        System.out.println("\n== 6) 真实接口：假 Cookie 应返回 401 ==");
        relaxTls();
        Dumate.Response live = Dumate.status(dm, cookie, Dumate.csrfOf(cookie));
        check("HTTP 401", live.http.code == 401, String.valueOf(live.http.code));
        check("业务码 10001", live.code == 10001, String.valueOf(live.code));
        check("文案为登录过期", Dumate.errorOf(live).contains("登录"), Dumate.errorOf(live));

        System.out.println("\n" + (failed == 0 ? "全部通过" : ("失败 " + failed + " 项")));
        if (failed != 0) {
            System.exit(1);
        }
    }
}