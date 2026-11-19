package com.marvis.signin.net;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.marvis.signin.data.Store;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 应用内浏览器会话引擎：处理 action_type = "web" 的福利活动。
 *
 * 服务端把部分签到/领取入口标记为网页型，纯 HTTP 领不了——需要在真实浏览器
 * 会话里打开活动页，由页面脚本完成签到。本类用一个无界面的 WebView 承担
 * 浏览器会话：注入 operationToken / user_token 登录态 Cookie，加载活动页，
 * 自动定位并点击「签到 / 领取」类按钮（含确认弹窗的二次点击）。
 *
 * 运行方式：WebView 创建在专用 HandlerThread（自带 Looper）上，任何后台线程
 * 都可以同步调用 claim()，不会和主线程互相阻塞。最终是否签到成功不由页面
 * 判断，而是由调用方重新查询每日任务接口复核（以服务端状态为准）。
 *
 * 已知限制：
 *   1. 按钮文案按常见词匹配（签到/打卡/领取/确认…），页面结构大改时可能点不中；
 *   2. 页面若要求真人验证（滑块/验证码）或严格设备指纹，自动流程会失败，
 *      编排层会回退为「请打开桌面端手动签到」。
 */
public class WebSessionClaimer {

    /** 一次页面尝试的结论。 */
    public static class Result {
        public boolean launched = false;    // 页面是否加载成功（onPageFinished 至少一次）
        public boolean clicked = false;     // 是否命中并点击了疑似签到/领取按钮
        public String clickedText = "";     // 点击到的按钮文案
        public String pageUrl = "";         // 实际加载到的地址（可能被页面重定向）
        public String detail = "";          // 未命中/失败时的说明
    }

    /** 桌面端 UA：福利社是桌面端专属功能，页面可能按 UA 出不同入口。 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 登录态 Cookie 的宿主（与福利社页面同域）。 */
    private static final String HOST = "https://agent.teleai.com.cn/";

    /**
     * 打开活动页并自动点击签到/领取按钮。
     *
     * @param url       活动页完整地址（TeleAgent.pageUrl 归一化后的值）
     * @param token     登录令牌（operationToken，与 HTTP 流程同一个值）
     * @param timeoutMs 总预算（毫秒），含页面加载与全部点击轮次
     * @return 页面尝试结论；任何异常都被吞掉并写进 detail，绝不向上抛
     */
    public static Result claim(String url, String token, long timeoutMs) {
        Result res = new Result();
        if (url == null || url.trim().length() == 0) {
            res.detail = "活动页地址为空";
            return res;
        }
        if (Store.APP == null) {
            res.detail = "应用上下文未就绪";
            return res;
        }
        final String fUrl = url.trim();
        final String fToken = token == null ? "" : token.trim();
        final long budget = timeoutMs <= 0 ? 15000L : timeoutMs;
        final Result fres = res;
        final CountDownLatch done = new CountDownLatch(1);
        final WebView[] holder = new WebView[1];

        // 登录态 Cookie：网页端鉴权读 operationToken，桌面客户端分区用 user_token，双键同值
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (fToken.length() > 0) {
                cm.setCookie(HOST, "operationToken=" + fToken + "; Path=/");
                cm.setCookie(HOST, "user_token=" + fToken + "; Path=/");
                cm.flush();
            }
        } catch (Throwable ignored) {
        }

        final HandlerThread ht = new HandlerThread("webclaim");
        ht.start();
        final Handler h = new Handler(ht.getLooper());

        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView wv = createWebView();
                    holder[0] = wv;
                    wv.setWebViewClient(new WebViewClient() {
                        private int passes = 0;

                        @Override
                        public void onPageFinished(final WebView view, String u) {
                            fres.launched = true;
                            fres.pageUrl = u;
                            if (passes >= 4) {
                                done.countDown();
                                return;
                            }
                            passes++;
                            final int pass = passes;
                            h.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        view.evaluateJavascript(jsClickPass(pass), new ValueCallback<String>() {
                                            @Override
                                            public void onReceiveValue(String value) {
                                                applyJsResult(fres, value);
                                                if (pass >= 4) {
                                                    done.countDown();
                                                }
                                            }
                                        });
                                    } catch (Throwable t) {
                                        if (fres.detail.length() == 0) {
                                            fres.detail = "页面脚本异常：" + t.getClass().getSimpleName();
                                        }
                                        done.countDown();
                                    }
                                }
                            }, pass == 1 ? 800L : 2000L);
                        }
                    });
                    // 硬超时：无论如何结束等待，避免卡死调用线程
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            done.countDown();
                        }
                    }, budget);
                    wv.loadUrl(fUrl);
                } catch (Throwable t) {
                    fres.detail = "WebView 异常：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : (": " + t.getMessage()));
                    done.countDown();
                }
            }
        });

        try {
            done.await(budget + 8000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }

        // 清理：销毁 WebView 并退出线程
        try {
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (holder[0] != null) {
                            holder[0].stopLoading();
                            holder[0].destroy();
                            holder[0] = null;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            ht.quitSafely();
        } catch (Throwable ignored) {
        }
        if (fres.detail.length() == 0 && !fres.launched) {
            fres.detail = "页面加载超时";
        }
        return res;
    }

    /** 无界面 WebView：JS + DOM 存储 + 桌面端 UA，不挂到任何窗口上。 */
    @SuppressLint("SetJavaScriptEnabled")
    private static WebView createWebView() {
        WebView wv = new WebView(Store.APP);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(DESKTOP_UA);
        wv.setVisibility(View.INVISIBLE);
        return wv;
    }

    /** 把 evaluateJavascript 返回的 JSON 字符串还原成 JS 侧的原始文案。 */
    private static String unwrapJs(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                v = new org.json.JSONObject("{\"v\":" + v + "}").optString("v", v);
            } catch (Throwable ignored) {
                v = v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    private static void applyJsResult(Result res, String value) {
        String v = unwrapJs(value);
        if (v.startsWith("CLICKED:")) {
            res.clicked = true;
            res.clickedText = v.substring(8);
        } else if (v.startsWith("NONE:")) {
            if (res.detail.length() == 0) {
                res.detail = v.substring(5);
            }
        } else if (v.startsWith("ERR:")) {
            if (res.detail.length() == 0) {
                res.detail = "脚本执行失败：" + v.substring(4);
            }
        }
    }

    /**
     * 自动点击脚本。
     * 第 1 轮找主按钮（立即签到/签到/打卡/领取…），第 2 轮起找确认类按钮（确认/知道了…），
     * 覆盖「点击后弹确认框」的常见流程。完全匹配优先于包含匹配，排除导航类文案。
     */
    private static String jsClickPass(int pass) {
        boolean confirm = pass >= 2;
        String keys = confirm
                ? "['确认','确定','立即领取','知道了','继续领取','马上领']"
                : "['立即签到','去签到','签到','打卡','立即领取','领取奖励','领取','去完成']";
        return "(function(){try{"
                + "var keys=" + keys + ";"
                + "var bad=/日历|记录|规则|说明|明细|排行|列表|攻略|教程|详情|中心/;"
                + "var els=document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span,li,p');"
                + "var best=null,bs=-1,bt='';"
                + "for(var i=0;i<els.length;i++){"
                + " var el=els[i];"
                + " var t=(el.innerText||el.textContent||el.value||'').replace(/\\s+/g,'');"
                + " if(!t||t.length>12||bad.test(t))continue;"
                + " for(var k=0;k<keys.length;k++){"
                + "  if(t.indexOf(keys[k])<0)continue;"
                + "  var r=el.getBoundingClientRect();"
                + "  if(r.width<2||r.height<2)continue;"
                + "  var st=window.getComputedStyle(el);"
                + "  if(st.display==='none'||st.visibility==='hidden'||st.pointerEvents==='none')continue;"
                + "  var sc=(t===keys[k]?50:0)+(keys.length-k)*5+(12-t.length);"
                + "  if(sc>bs){bs=sc;best=el;bt=t;}"
                + "  break;"
                + " }"
                + "}"
                + "if(best){best.click();return 'CLICKED:'+bt;}"
                + "var b=document.body?document.body.innerText:'';"
                + "return 'NONE:'+(b.indexOf('已签到')>=0||b.indexOf('已完成')>=0||b.indexOf('明日再来')>=0"
                + "?'页面已显示完成状态':'未找到可点击的签到按钮');"
                + "}catch(e){return 'ERR:'+(e&&e.message?e.message:String(e));}})()";
    }
}
