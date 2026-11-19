package com.marvis.signin.net;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.util.Http;

import java.util.ArrayList;
import java.util.List;

/**
 * 星辰超级智能体 TeleAgent 签到编排。
 *
 * 一次签到 = 查福利社每日任务 -> 主活动（每日签到）可领才发领取 -> 顺带领取其余可领的福利活动。
 *
 * 关键点：
 *   1. 鉴权靠 X-Token（即福利社网页 Cookie 的 operationToken），失效时提示重新获取；
 *   2. action_status = completed 表示今天已完成，不再发领取请求；
 *   3. action_type = "web" 的活动需要浏览器会话码，纯 HTTP 领不了，只计数并明确提示；
 *   4. 单条活动领取失败不中断，继续领后面的，最后汇总结果；
 *   5. 状态接口说没有可领内容时，一个多余的写请求都不发。
 */
public class TeleAgentSign {

    /** 一次签到的结论。 */
    public static class Verdict {
        public boolean ok = false;
        public boolean already = false;
        public String message = "";
    }

    /** 执行一次 TeleAgent 签到 + 福利社活动领取。 */
    public static Verdict run(Platform p) {
        Verdict v = new Verdict();

        if (!p.hasTeleagentToken()) {
            v.message = "未导入令牌：请点进配置页，粘贴福利社网页的 operationToken 后点「保存并启用」";
            return v;
        }

        String token = p.credValue == null ? "" : p.credValue.trim();
        String deviceId = p.credDeviceId == null ? "" : p.credDeviceId.trim();

        // 1) 查福利社每日任务
        Http.Result st = TeleAgent.dayTask(token, deviceId);
        trace(p, st);
        if (st.error != null && st.error.length() > 0) {
            v.message = "查询福利社失败：" + st.error;
            return v;
        }
        if (st.code == 401 || st.code == 403) {
            v.message = "登录态已失效（HTTP " + st.code + "），请重新从福利社网页获取 operationToken";
            return v;
        }
        if (!st.httpOk()) {
            v.message = "查询福利社失败：HTTP " + st.code + " · " + TeleAgent.upstreamError(st);
            return v;
        }
        if (!TeleAgent.bizOk(st)) {
            v.message = "查询福利社失败：" + TeleAgent.upstreamError(st);
            return v;
        }
        TeleAgent.TaskData td = TeleAgent.parseTask(st);
        if (td == null) {
            v.message = "查询福利社失败：响应里没有 activity_data（接口可能已调整），请抓包核对后更新";
            return v;
        }

        // 2) 主活动 = 每日签到；其余可见活动按可领性分类
        TeleAgent.Activity main = null;
        List<TeleAgent.Activity> others = new ArrayList<TeleAgent.Activity>();
        List<TeleAgent.Activity> webOnly = new ArrayList<TeleAgent.Activity>();
        boolean anyVisible = false;
        for (TeleAgent.Activity a : td.activities) {
            if (!a.visible) {
                continue;
            }
            anyVisible = true;
            if (a.main) {
                main = a;
            } else if (a.claimable() && isWebType(a)) {
                webOnly.add(a);
            } else if (a.claimable()) {
                others.add(a);
            }
        }
        if (!anyVisible) {
            v.ok = true;
            v.message = "HTTP " + st.code + " · " + st.cost + "ms · 福利社暂无可参加的活动";
            return v;
        }
        // 签到主活动竟然是网页类型：纯 HTTP 领不了，明确告知，不做无效请求
        if (main != null && main.claimable() && isWebType(main)) {
            v.message = "HTTP " + st.code + " · " + st.cost
                    + "ms · 签到入口需要浏览器会话（action_type=web），请打开桌面端 TeleAgent 手动签到";
            return v;
        }

        StringBuilder detail = new StringBuilder();
        int claimed = 0;
        int failed = 0;

        // 3) 每日签到
        boolean signedNow = false;
        if (main != null && main.actionStatus.equalsIgnoreCase("completed")) {
            v.already = true;
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("今日已签到");
        } else if (main != null && main.claimable()) {
            Http.Result cl = TeleAgent.claim(token, deviceId, main.jumpUrl);
            trace(p, cl);
            if (cl.error != null && cl.error.length() > 0) {
                v.message = "签到失败：" + cl.error;
                return v;
            }
            if (cl.code == 401 || cl.code == 403) {
                v.message = "登录态已失效（HTTP " + cl.code + "），请重新获取 operationToken";
                return v;
            }
            if (!cl.httpOk() || !TeleAgent.bizOk(cl)) {
                v.message = "签到失败：HTTP " + cl.code + " · " + TeleAgent.upstreamError(cl);
                return v;
            }
            signedNow = true;
            claimed++;
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("签到成功");
            if (main.points > 0) {
                detail.append(" +").append(main.points).append(" 积分");
            }
        } else if (main == null) {
            // 接口结构变化时没有主活动：只领其余活动，最后按领取结果判定成败
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("未识别到签到主活动");
        } else if (!main.claimable()) {
            // 主活动既不是 completed 也不可领（如活动已结束）：没有可做的事，不算失败
            v.already = true;
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("今日签到已结束或不可领（").append(main.actionStatus).append("）");
        }

        // 4) 其余可领的福利活动
        for (int i = 0; i < others.size(); i++) {
            TeleAgent.Activity a = others.get(i);
            if (i > 0) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            Http.Result cl = TeleAgent.claim(token, deviceId, a.jumpUrl);
            trace(p, cl);
            boolean okOne = cl.error == null || cl.error.length() == 0;
            if (okOne) {
                okOne = cl.httpOk() && TeleAgent.bizOk(cl);
            }
            if (okOne) {
                claimed++;
            } else {
                failed++;
            }
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append(okOne ? "领到「" : "「").append(shortName(a.title))
                    .append(okOne ? "」" : "」领取失败");
            if (okOne && a.points > 0) {
                detail.append(" +").append(a.points);
            }
        }

        // 5) 汇总：主活动已完成/已结束、本次签到成功、至少领到一项，都算成功
        boolean done = v.already || signedNow || claimed > 0;
        v.ok = done;
        int web = webOnly.size();
        if (web > 0) {
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append(web).append(" 项网页活动需在客户端打开领取");
        }
        String head = "HTTP " + st.code + " · " + st.cost + "ms · ";
        if (v.ok) {
            String tag = signedNow ? "签到成功" : (v.already ? "已签到" : "已完成");
            v.message = head + tag + (detail.length() > 0 ? "（" + detail + "）" : "");
        } else {
            v.message = head + "本次未能领取（" + detail + "）";
        }
        if (failed > 0) {
            v.message += "，" + failed + " 项失败";
        }
        return v;
    }

    private static boolean isWebType(TeleAgent.Activity a) {
        return "web".equalsIgnoreCase(a.actionType == null ? "" : a.actionType.trim());
    }

    private static String shortName(String title) {
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) {
            return "福利活动";
        }
        return t.length() <= 12 ? t : t.substring(0, 12) + "…";
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
