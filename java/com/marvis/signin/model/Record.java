package com.marvis.signin.model;

import org.json.JSONObject;

/** 一次签到结果。 */
public class Record {

    public String platformId = "";
    public String platformName = "";
    public boolean success;
    public long time;
    public String message = "";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pid", platformId);
            o.put("pname", platformName);
            o.put("ok", success);
            o.put("t", time);
            o.put("msg", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Record fromJson(JSONObject o) {
        Record r = new Record();
        r.platformId = o.optString("pid", "");
        r.platformName = o.optString("pname", "");
        r.success = o.optBoolean("ok", false);
        r.time = o.optLong("t", 0);
        r.message = o.optString("msg", "");
        return r;
    }
}
