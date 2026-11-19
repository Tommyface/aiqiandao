package com.marvis.signin.model;

import org.json.JSONObject;

/** 一次签到请求的抓包快照。 */
public class Trace {
    public long time = 0;
    public String platformId = "";
    public String platformName = "";
    public String method = "";
    public String url = "";
    public int code = -1;
    public long cost = 0;
    public String reqDump = "";
    public String respDump = "";
    public String error = "";
    /** 本次响应回收到的 Cookie 条数。 */
    public int cookieCount = 0;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("time", time);
            o.put("pid", platformId);
            o.put("pname", platformName);
            o.put("method", method);
            o.put("url", url);
            o.put("code", code);
            o.put("cost", cost);
            o.put("req", reqDump);
            o.put("resp", respDump);
            o.put("error", error);
            o.put("ck", cookieCount);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Trace fromJson(JSONObject o) {
        Trace t = new Trace();
        t.time = o.optLong("time");
        t.platformId = o.optString("pid", "");
        t.platformName = o.optString("pname", "");
        t.method = o.optString("method", "");
        t.url = o.optString("url", "");
        t.code = o.optInt("code", -1);
        t.cost = o.optLong("cost");
        t.reqDump = o.optString("req", "");
        t.respDump = o.optString("resp", "");
        t.error = o.optString("error", "");
        t.cookieCount = o.optInt("ck", 0);
        return t;
    }

    public Trace copy() {
        return fromJson(toJson());
    }
}
