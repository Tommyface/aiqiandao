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
 *   3. action_type = "web" 的活动走应用内浏览器会话（WebSessionClaimer）自动完成，
 *      成败以重新查询每日任务接口的服务端状态为准；
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

    /** 执行一次 TeleAgent 签到 + 福利社活动领取（网页型活动走应用内浏览器会话）。 */
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
        StringBuilder detail = new StringBuilder();
        int claimed = 0;
        int failed = 0;
        boolean signedNow = false;
        boolean mainIsWeb = main != null && main.claimable() && isWebType(main);

        // 3) 每日签到
        if (main != null && main.actionStatus.equalsIgnoreCase("completed")) {
            v.already = true;
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("今日已签到");
        } else if (main != null && main.claimable() && !mainIsWeb) {
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
        } else if (mainIsWeb) {
            // 网页型签到入口：应用内浏览器会话自动完成，成败稍后以接口复核为准
            if (Store.APP != null) {
                WebSessionClaimer.Result wr = WebSessionClaimer.claim(
                        TeleAgent.pageUrl(main.jumpUrl), token, 25000L);
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("网页签到（").append(webAttemptText(wr)).append("）");
            } else {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("签到入口在网页端，需桌面端手动完成");
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

        // 4.5) 其余网页型活动：逐个用应用内浏览器会话尝试（最多 2 个，控制总时长）
        boolean webAttempted = mainIsWeb && Store.APP != null;
        int webTried = 0;
        for (int i = 0; i < webOnly.size() && webTried < 2; i++) {
            if (Store.APP == null) {
                break;
            }
            TeleAgent.Activity a = webOnly.get(i);
            webAttempted = true;
            webTried++;
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            WebSessionClaimer.Result wr = WebSessionClaimer.claim(
                    TeleAgent.pageUrl(a.jumpUrl), token, 15000L);
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("网页活动「").append(shortName(a.title))
                    .append(wr.clicked ? "」已尝试自动点击「" + wr.clickedText + "」" : "」未命中页面按钮");
        }

        // 5) 复核：做过网页尝试时重新查一次任务，以服务端状态为准
        boolean webMainDone = false;
        int webDoneCount = 0;
        if (webAttempted) {
            Http.Result st2 = TeleAgent.dayTask(token, deviceId);
            trace(p, st2);
            if (st2.error == null && st2.error.length() == 0 && st2.httpOk() && TeleAgent.bizOk(st2)) {
                TeleAgent.TaskData td2 = TeleAgent.parseTask(st2);
                if (td2 != null) {
                    for (TeleAgent.Activity a : td2.activities) {
                        if (!a.visible || !"completed".equalsIgnoreCase(a.actionStatus)) {
                            continue;
                        }
                        if (a.main) {
                            if (mainIsWeb) {
                                webMainDone = true;
                            }
                        } else if (isWebType(a)) {
                            for (TeleAgent.Activity w : webOnly) {
                                if (w.title != null && w.title.equals(a.title)) {
                                    webDoneCount++;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6) 汇总：主活动已完成/已结束、本次签到成功、至少领到一项，都算成功
        if (webMainDone) {
            signedNow = true;
        }
        boolean done = v.already || signedNow || claimed > 0;
        v.ok = done;
        String head = "HTTP " + st.code + " · " + st.cost + "ms · ";
        if (v.ok) {
            String tag = signedNow ? "签到成功" : (v.already ? "已签到" : "已完成");
            if (signedNow && webMainDone) {
                tag = "签到成功（应用内浏览器自动完成）";
            }
            v.message = head + tag + (detail.length() > 0 ? "（" + detail + "）" : "");
            if (webDoneCount > 0) {
                v.message += "，网页活动复核完成 " + webDoneCount + " 项";
            }
        } else {
            v.message = head + "本次未能领取（" + detail + "），请打开桌面端 TeleAgent 手动签到";
        }
        if (failed > 0) {
            v.message += "，" + failed + " 项失败";
        }
        return v;
    }

    private static boolean isWebType(TeleAgent.Activity a) {
        return "web".equalsIgnoreCase(a.actionType == null ? "" : a.actionType.trim());
    }

    /** 网页会话尝试结果的展示文案：命中按钮只报按钮；未命中时带上页面标题/内容/按钮清单，方便定位。 */
    private static String webAttemptText(WebSessionClaimer.Result wr) {
        if (wr.clicked) {
            return "已自动点击「" + wr.clickedText + "」";
        }
        if (!wr.launched) {
            return "未能打开活动页：" + wr.detail;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已打开活动页，未命中按钮");
        if (wr.detail != null && wr.detail.length() > 0) {
            sb.append("：").append(wr.detail);
        }
        if (wr.pageTitle != null && wr.pageTitle.trim().length() > 0) {
            sb.append("｜标题「").append(clip(wr.pageTitle, 16)).append("」");
        }
        if (wr.buttons != null && wr.buttons.length() > 0) {
            sb.append("｜页面按钮[").append(clip(wr.buttons, 60)).append("]");
        }
        if (wr.pageText != null && wr.pageText.trim().length() > 0) {
            sb.append("｜内容「").append(clip(wr.pageText, 40)).append("」");
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
