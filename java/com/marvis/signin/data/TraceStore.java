package com.marvis.signin.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.marvis.signin.model.Platform;
import com.marvis.signin.model.Trace;
import com.marvis.signin.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 抓包助手：本地保存最近若干次请求的完整报文，配接口时无需外部抓包工具。 */
public class TraceStore {

    private static final String PREF = "signin_trace_v1";
    private static final String K = "traces";
    private static final int MAX = 60;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 记录一次请求，返回 Cookie 回收条数。 */
    public static int record(Context c, Platform p, Http.Result r, int cookieCount) {
        if (c == null || p == null || r == null) {
            return 0;
        }
        try {
            List<Trace> list = list(c);
            Trace t = new Trace();
            t.time = System.currentTimeMillis();
            t.platformId = p.id == null ? "" : p.id;
            t.platformName = p.name == null ? "" : p.name;
            t.method = p.method == null ? "POST" : p.method.toUpperCase();
            t.url = p.url == null ? "" : p.url;
            t.code = r.code;
            t.cost = r.cost;
            t.reqDump = r.reqDump == null ? "" : r.reqDump;
            t.respDump = r.respDump == null ? "" : r.respDump;
            t.error = r.error == null ? "" : r.error;
            t.cookieCount = cookieCount;
            list.add(0, t);
            while (list.size() > MAX) {
                list.remove(list.size() - 1);
            }
            save(c, list);
        } catch (Exception ignored) {
        }
        return cookieCount;
    }

    public static List<Trace> list(Context c) {
        List<Trace> out = new ArrayList<Trace>();
        if (c == null) {
            return out;
        }
        try {
            String raw = sp(c).getString(K, "");
            if (raw == null || raw.length() == 0) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Trace.fromJson(arr.optJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void clear(Context c) {
        if (c == null) {
            return;
        }
        sp(c).edit().remove(K).apply();
    }

    private static void save(Context c, List<Trace> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Trace t : list) {
                arr.put(t.toJson());
            }
            sp(c).edit().putString(K, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
