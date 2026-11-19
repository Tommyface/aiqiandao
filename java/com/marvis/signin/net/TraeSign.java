package com.marvis.signin.net;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.util.Http;

/**
 * Trae CN 签到编排。
 *
 * 一次签到 = 查状态 -> 今天可领才发 claim。
 *
 * 关键点：
 *   1. Authorization 必须是 Cloud-IDE-JWT <token>；
 *   2. claim 必须带 x-device-id，否则上游返回 9004；
 *   3. 上游返回 9074 表示只允许官方客户端领取，属于不可绕过的限制，直接给出明确提示；
 *   4. 状态接口说今天不可领时，一个写请求都不发。
 */
public class TraeSign {

    /** 一次签到的结论。 */
    public static class Verdict {
        public boolean ok = false;
        public boolean already = false;
        public String message = "";
    }

    /** 执行一次 Trae 签到。 */
    public static Verdict run(Platform p) {
        Verdict v = new Verdict();

        if (!p.hasTraeToken()) {
            v.message = "未导入登录态：请点进配置页，粘贴 Trae 客户端的 storage.json 后点「解析并保存」";
            return v;
        }

        String token = p.credValue == null ? "" : p.credValue.trim();
        String deviceId = p.credDeviceId == null ? "" : p.credDeviceId.trim();

        // 1) 查签到状态
        Http.Result st = Trae.status(token, deviceId);
        trace(p, st);
        if (st.error != null && st.error.length() > 0) {
            v.message = "查询签到状态失败：" + st.error;
            return v;
        }
        if (st.code == 401 || st.code == 403) {
            v.message = "登录态已失效（HTTP " + st.code + "），请重新从 Trae 客户端导入 storage.json";
            return v;
        }
        if (!st.httpOk()) {
            v.message = "查询签到状态失败：HTTP " + st.code + " · " + Trae.upstreamError(st);
            return v;
        }
        int code = Trae.codeOf(st);
        if (code == 1001) {
            v.message = "登录态已失效（code 1001），请重新从 Trae 客户端导入 storage.json";
            return v;
        }
        if (code != 0) {
            v.message = "查询签到状态失败：" + Trae.upstreamError(st);
            return v;
        }

        // 2) 今天可领才发 claim
        if (!Trae.isEnabled(st)) {
            v.ok = true;
            v.message = "HTTP " + st.code + " · " + st.cost + "ms · 当前没有可领取的签到活动";
            return v;
        }
        if (Trae.isCheckedIn(st)) {
            int pts = Trae.credits(st);
            v.ok = true;
            v.already = true;
            v.message = "HTTP " + st.code + " · " + st.cost + "ms · 今天已签到"
                    + (pts > 0 ? "（今日 " + pts + " 积分）" : "");
            return v;
        }

        Http.Result cl = Trae.claim(token, deviceId);
        trace(p, cl);
        if (cl.error != null && cl.error.length() > 0) {
            v.message = "领取失败：" + cl.error;
            return v;
        }
        if (cl.code == 401 || cl.code == 403) {
            v.message = "登录态已失效（HTTP " + cl.code + "），请重新从 Trae 客户端导入 storage.json";
            return v;
        }
        if (!cl.httpOk()) {
            v.message = "领取失败：HTTP " + cl.code + " · " + Trae.upstreamError(cl);
            return v;
        }

        int clCode = Trae.codeOf(cl);
        if (clCode == 0) {
            int pts = Trae.credits(cl);
            v.ok = true;
            v.message = "HTTP " + cl.code + " · " + cl.cost + "ms · 签到成功"
                    + (pts > 0 ? " +" + pts + " 积分" : "");
        } else if (clCode == 9074) {
            v.message = "Trae 拒绝第三方签到（9074），请打开官方 Trae 客户端签到";
        } else if (clCode == 9004) {
            v.message = "缺少设备号（9004），请回到配置页重新导入 storage.json 以绑定 x-device-id";
        } else if (clCode == 1001) {
            v.message = "登录态已失效（code 1001），请重新从 Trae 客户端导入 storage.json";
        } else {
            v.message = "领取失败：" + Trae.upstreamError(cl);
        }
        return v;
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
}
