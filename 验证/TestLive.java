import com.marvis.signin.net.Minimax;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;

/**
 * 端到端打真实接口：验证 Java 侧的表单编码、PKCE、设备码申请与 pending 判定。
 * 注意：沙箱出网经过 ACK 代理（每连接换自签证书），所以测试夹具里放开 TLS 校验；
 * 真机直连 MiniMax 官方证书链，不受此影响。
 */
public class TestLive {

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
        relaxTls();
        System.out.println("== 1) 申请设备码（真实请求 account.minimax.cn）==");
        Minimax.DeviceCode dc = Minimax.requestDeviceCode();
        System.out.println("   device_code 前 12 位 : " + dc.deviceCode.substring(0, 12) + "…");
        System.out.println("   user_code             : " + dc.userCode);
        System.out.println("   verification_uri      : " + dc.verificationUri);
        System.out.println("   uri_complete          : " + dc.verificationUriComplete);
        System.out.println("   expires_in / interval : " + dc.expiresIn + " / " + dc.interval + " 秒");
        if (dc.deviceCode.isEmpty() || dc.userCode.isEmpty()) {
            System.out.println("!! 设备码响应缺字段");
            System.exit(1);
        }

        System.out.println("\n== 2) 未在浏览器确认时轮询（应判定为 pending，不抛异常）==");
        Minimax.Token t = Minimax.pollToken(dc);
        System.out.println("   pollToken 返回 = " + (t == null ? "null（正确：仍在等待用户授权）" : t.accessToken));
        if (t != null) {
            System.out.println("!! 尚未授权却拿到了令牌，判定逻辑有问题");
            System.exit(1);
        }

        System.out.println("\n== 3) 真实签到接口的鉴权失败路径（用假 token）==");
        com.marvis.signin.util.Http.Result st = Minimax.status("fake_token_for_error_path");
        System.out.println("   status -> HTTP " + st.code + " · " + st.body);
        if (st.code != 401) {
            System.out.println("!! 预期 401，实际 " + st.code);
            System.exit(1);
        }
        com.marvis.signin.util.Http.Result cl = Minimax.claim("fake_token_for_error_path");
        System.out.println("   claim  -> HTTP " + cl.code + " · " + cl.body);
        if (cl.code != 401) {
            System.out.println("!! 预期 401，实际 " + cl.code);
            System.exit(1);
        }
        System.out.println("\n全部通过：Java 侧的授权与签到请求链路与真实接口契约一致。");
    }
}
