package com.marvis.signin.net;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.marvis.signin.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * 线程纪律（v1.7.2 修正）：View / WebView / WebSettings / CookieManager 全部
 * 要求在主线程调用，本类所有 WebView 相关操作统一 post 到主线程 Handler 执行；
 * 调用方是后台工作线程，在闭锁上等待结果；主线程直接调用会被立即拒绝。
 *
 * v1.7.5 修正（真机诊断「页面有内容但找不到任何按钮」后，两个根因）：
 *   - 无界面 WebView 从不挂窗口，不 layout 就没有尺寸：内容视口宽高为 0，
 *     所有元素 getBoundingClientRect 都是 0，按可见性过滤时全部被筛掉。
 *     现在创建后强制 measure + layout 到 1080x1920，页面有真实视口；
 *   - 点击轮次原先挂在 onPageFinished 上，页面只加载一次就只跑第 1 轮，
 *     「滚动 + 多轮重试」形同虚设。改为固定时间表的自调度轮次：
 *     加载后 1.2s 跑第 1 轮，之后每 2.5s 一轮（第 2 轮起先滚动触发懒加载），
 *     共 5 轮；onPageFinished 只负责标记页面已加载；
 *   - 最后一轮仍未命中时，抓取页面 HTML 快照（前 6000 字符）回传，
 *     由编排层记进抓包助手，用户可直接在 App 里查看页面真实 DOM。
 *
 * 最终是否签到成功不由页面判断，而是由调用方重新查询每日任务接口复核
 * （以服务端状态为准）。
 *
 * 已知限制：
 *   1. 按钮文案按常见词匹配，页面结构大改时可能点不中（此时报错会带页面片段
 *      与 HTML 快照，便于定位）；
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
        public String pageTitle = "";      // 页面标题
        public String pageText = "";       // 页面可见内容片段（200 字）
        /** v1.7.4：页面上实际存在的按钮类元素文案（分隔符 /），用于定位该点什么。 */
        public String buttons = "";
        /** v1.7.5：最后一轮未命中时的页面 HTML 快照（前 6000 字符），供抓包助手排查。 */
        public String pageHtml = "";
        public String detail = "";          // 未命中/失败时的说明
    }

    /** 桌面端 UA：福利社是桌面端专属功能，页面可能按 UA 出不同入口。 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 登录态 Cookie 的宿主（与福利社页面同域）。 */
    private static final String HOST = "https://agent.teleai.com.cn/";

    /** 点击轮数：第 1 轮找主按钮，第 2-5 轮滚动 + 找确认类按钮。 */
    private static final int MAX_PASSES = 5;

    /** 无界面 WebView 的强制视口（不挂窗口也必须有尺寸，否则页面内容视口为 0）。 */
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 本方法会闭锁等待主线程执行 WebView，主线程里调用等于自我死锁
            res.detail = "内部错误：浏览器会话不能在主线程运行";
            return res;
        }
        final String fUrl = url.trim();
        final String fToken = token == null ? "" : token.trim();
        final long budget = timeoutMs <= 0 ? 15000L : timeoutMs;
        final Result fres = res;
        final CountDownLatch done = new CountDownLatch(1);
        final WebView[] holder = new WebView[1];
        // 主线程 Handler：WebView 的创建、加载、脚本执行、销毁全部在这里排队执行
        final Handler h = new Handler(Looper.getMainLooper());

        // 全部 WebView 相关操作都在主线程执行（View 方法只允许主线程调用）
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
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

                    final WebView wv = createWebView();
                    holder[0] = wv;
                    // onPageFinished 只负责标记「页面已加载」与记录地址
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String u) {
                            fres.launched = true;
                            if (u != null && u.trim().length() > 0) {
                                fres.pageUrl = u;
                            }
                        }
                    });

                    // 固定时间表的自调度点击轮次：加载后 1.2s 第 1 轮，之后每 2.5s 一轮，
                    // 共 MAX_PASSES 轮；第 2 轮起脚本先滚动触发懒加载。末轮抓 HTML 快照。
                    final Runnable[] passBox = new Runnable[1];
                    final int[] passNo = new int[]{0};
                    passBox[0] = new Runnable() {
                        @Override
                        public void run() {
                            if (holder[0] == null) {
                                return; // 已被清理
                            }
                            passNo[0]++;
                            final int pass = passNo[0];
                            if (pass > MAX_PASSES) {
                                done.countDown();
                                return;
                            }
                            final boolean last = pass == MAX_PASSES;
                            try {
                                holder[0].evaluateJavascript(jsClickPass(pass, last), new ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String value) {
                                        applyJsResult(fres, value);
                                        if (last) {
                                            done.countDown();
                                        } else {
                                            // 第 1 轮给页面多点渲染时间，后续轮次 2.5s 间隔
                                            h.postDelayed(passBox[0], pass == 1 ? 1500L : 2500L);
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
                    };
                    h.postDelayed(passBox[0], 1200L);

                    // 硬超时兜底：无论如何结束等待，避免卡死调用线程
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

        // 调用方是工作线程：等主线程把页面流程跑完（或超时）
        try {
            done.await(budget + 8000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }

        // 清理：同样必须回主线程销毁 WebView
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (holder[0] != null) {
                        holder[0].stopLoading();
                        holder[0].removeAllViews();
                        holder[0].destroy();
                        holder[0] = null;
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        if (fres.detail.length() == 0 && !fres.launched) {
            fres.detail = "页面加载超时";
        }
        return res;
    }

    /**
     * 无界面 WebView：JS + DOM 存储 + 桌面端 UA。
     * 关键：不挂窗口也必须强制 measure + layout 出尺寸，否则内容视口宽高为 0，
     * 页面元素的 getBoundingClientRect 全为 0，按可见性过滤会全军覆没。
     */
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
        // 强制布局出真实视口尺寸（无界面 WebView 的关键一步）
        int wSpec = View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY);
        wv.measure(wSpec, hSpec);
        wv.layout(0, 0, VIEW_W, VIEW_H);
        return wv;
    }

    /** 把 evaluateJavascript 返回的 JSON 字符串还原成 JS 侧的原始字符串。 */
    private static String unwrapJs(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                v = new JSONObject("{\"v\":" + v + "}").optString("v", v);
            } catch (Throwable ignored) {
                v = v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** 解析页面脚本回传的 JSON 结论：clicked/text/why/title/body/btns/html。 */
    private static void applyJsResult(Result res, String value) {
        String v = unwrapJs(value);
        if (v.length() == 0) {
            return;
        }
        try {
            JSONObject o = new JSONObject(v);
            if (o.optBoolean("clicked", false)) {
                res.clicked = true;
                res.clickedText = o.optString("text", "");
            } else if (res.detail.length() == 0) {
                res.detail = o.optString("why", "");
            }
            String t = o.optString("title", "");
            if (t.length() > 0) {
                res.pageTitle = t;
            }
            String b = o.optString("body", "");
            if (b.length() > 0) {
                res.pageText = b;
            }
            StringBuilder btns = new StringBuilder();
            JSONArray arr = o.optJSONArray("btns");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < 10; i++) {
                    String s = arr.optString(i, "");
                    if (s.length() == 0) {
                        continue;
                    }
                    if (btns.length() > 0) {
                        btns.append(" / ");
                    }
                    btns.append(s);
                }
            }
            if (btns.length() > 0) {
                res.buttons = btns.toString();
            }
            String html = o.optString("html", "");
            if (html.length() > 0) {
                res.pageHtml = html;
            }
        } catch (Throwable ignored) {
            // 非预期格式：忽略，不影响主流程
        }
    }

    /**
     * 自动点击脚本（每轮执行一次，返回 JSON 结论）。
     * 第 1 轮找主按钮（立即签到/签到/打卡/领取…），第 2 轮起先滚动再找确认类按钮。
     * 搜索范围：主文档 + 同源 iframe + Shadow DOM（逐层穿透）；完全匹配优先，排除导航类文案；
     * 命中后按完整指针事件序列触发。
     * 未命中时回传：页面标题 + 内容片段（200 字）+ 实际存在的按钮清单；
     * 末轮（captureHtml）再抓 HTML 快照（前 6000 字符）。
     */
    private static String jsClickPass(int pass, boolean captureHtml) {
        boolean confirm = pass >= 2;
        String keys = confirm
                ? "['确认','确定','立即领取','知道了','继续领取','马上领','好的','我知道了','领取','继续']"
                : "['立即签到','去签到','签到','打卡','立即领取','领取奖励','领取','去完成','立即参与','去领取','领积分','签到领','参加活动','马上签到','领取今日','加入','抽奖','立即加入','去参与']";
        return "(function(){"
                + "var out={clicked:false,text:'',why:'',title:(document.title||''),body:'',btns:[],html:''};"
                + "try{"
                // 后续轮次先滚动页面，触发懒加载/懒渲染
                + "if(" + (pass >= 2) + "){try{window.scrollBy(0,Math.round(window.innerHeight*0.8));}catch(e){}}"
                // 收集主文档 + 同源 iframe 的文档
                + "var docs=[document];"
                + "try{var fs=document.querySelectorAll('iframe');"
                + "for(var i=0;i<fs.length;i++){try{var d=fs[i].contentDocument;if(d)docs.push(d);}catch(e){}}}catch(e){}"
                // 递归收集元素（穿透 Shadow DOM）
                + "function scan(root,acc,depth){"
                + " if(depth>5)return;"
                + " var els;"
                + " try{els=root.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span,li,p,label,i');}catch(e){return;}"
                + " for(var i=0;i<els.length;i++){"
                + "  acc.push(els[i]);"
                + "  try{if(els[i].shadowRoot)scan(els[i].shadowRoot,acc,depth+1);}catch(e){}"
                + " }"
                + "}"
                // 逐文档扫描候选元素
                + "var keys=" + keys + ";"
                + "var bad=/日历|记录|规则|说明|明细|排行|列表|攻略|教程|详情|中心/;"
                + "var best=null,bs=-1,bt='',bd=null;"
                + "for(var di=0;di<docs.length;di++){var doc=docs[di];"
                + " var acc=[];"
                + " try{scan(doc,acc,0);}catch(e){continue;}"
                + " for(var i=0;i<acc.length;i++){"
                + "  var el=acc[i];"
                + "  var t=(el.innerText||el.textContent||el.value||'').replace(/\\s+/g,'');"
                + "  if(!t||t.length>12||bad.test(t))continue;"
                + "  for(var k=0;k<keys.length;k++){"
                + "   if(t.indexOf(keys[k])<0)continue;"
                + "   var r=el.getBoundingClientRect();"
                + "   if(r.width<2||r.height<2)continue;"
                + "   var st=(el.ownerDocument.defaultView||window).getComputedStyle(el);"
                + "   if(st.display==='none'||st.visibility==='hidden'||st.pointerEvents==='none')continue;"
                + "   var sc=(t===keys[k]?50:0)+(keys.length-k)*5+(12-t.length)+(di===0?10:0);"
                + "   if(sc>bs){bs=sc;best=el;bt=t;bd=di;}"
                + "   break;"
                + "  }"
                + " }"
                + "}"
                + "if(best){fire(best,bd);out.clicked=true;out.text=bt;"
                + " var b2=(bd===0?document.body:(docs[bd]?docs[bd].body:null));"
                + " out.body=snip(b2);return JSON.stringify(out);}"
                // 未命中：回传页面诊断（标题 + 内容 200 字 + 各文档按钮清单 + 末轮 HTML 快照）
                + "var b=document.body?document.body.innerText:'';"
                + "if(!b){for(var j=1;j<docs.length;j++){try{if(docs[j]&&docs[j].body){b=docs[j].body.innerText;break;}}catch(e){}}}"
                + "out.body=snip(b);"
                + "try{var seen={};"
                + " for(var dj=0;dj<docs.length;dj++){var d2=docs[dj];"
                + "  var bl;"
                + "  try{bl=d2.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit]');}catch(e){continue;}"
                + "  for(var m=0;m<bl.length&&out.btns.length<10;m++){"
                + "   var e2=bl[m];"
                + "   var t2=(e2.innerText||e2.textContent||e2.value||'').replace(/\\s+/g,'');"
                + "   if(!t2||t2.length>14||seen[t2])continue;"
                + "   try{var r2=e2.getBoundingClientRect();if(r2.width<2||r2.height<2)continue;"
                + "    var st2=getComputedStyle(e2);if(st2.display==='none'||st2.visibility==='hidden')continue;}catch(e3){}"
                + "   seen[t2]=1;out.btns.push(t2);"
                + "  }"
                + " }"
                + "}catch(e){}"
                + "var done=/已签到|已完成|明日再来|今日已领|已经签到/.test(b);"
                + "var login=/请登录|扫码登录|立即登录|登录后查看|未登录/.test(b);"
                + "out.why=done?'页面已显示完成状态':(login?'页面疑似要求登录（登录态 Cookie 可能未生效）':'未找到可点击的签到按钮');"
                + "if(" + captureHtml + "){try{out.html=(document.documentElement.outerHTML||'').substring(0,6000);}catch(e){}}"
                + "}catch(e){out.why='脚本异常:'+(e&&e.message?e.message:String(e));}"
                + "return JSON.stringify(out);"
                + "function snip(b){return (b||'').replace(/\\s+/g,' ').substring(0,200);}"
                + "function fire(el,di){"
                + " var w=(el.ownerDocument&&el.ownerDocument.defaultView)||window;"
                + " try{el.click();}catch(e){}"
                + " try{var r=el.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;"
                + "  var opts={bubbles:true,cancelable:true,view:w,clientX:x,clientY:y,button:0};"
                + "  try{el.dispatchEvent(new w.MouseEvent('mousedown',opts));"
                + "   el.dispatchEvent(new w.MouseEvent('mouseup',opts));}catch(e){}"
                + "  el.dispatchEvent(new w.MouseEvent('click',opts));"
                + " }catch(e){}"
                + "}"
                + "})()";
    }
}
