package com.marvis.signin.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cookie 自动续期。
 * 每次响应里的 Set-Cookie 会被解析合并进该平台的 Cookie 罐，
 * 下次发请求时自动拼成 Cookie 请求头。用户手写的 Cookie 优先，不会被覆盖。
 */
public class CookieJar {

    private static final String PREF = "signin_cookie_v1";
    private static final String K = "jar_";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 生成 Cookie 请求头；罐子为空返回 null。 */
    public static String header(Context c, String platformId) {
        Map<String, String> jar = load(c, platformId);
        if (jar.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<String, String> load(Context c, String platformId) {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        if (c == null || platformId == null || platformId.length() == 0) {
            return jar;
        }
        try {
            String raw = sp(c).getString(K + platformId, "");
            if (raw == null || raw.length() == 0) {
                return jar;
            }
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                jar.put(key, o.optString(key, ""));
            }
        } catch (Exception ignored) {
        }
        return jar;
    }

    /** 合并响应里的 Set-Cookie，返回本次写入/更新的条数。 */
    public static int absorb(Context c, String platformId, List<String> setCookies) {
        if (c == null || platformId == null || platformId.length() == 0
                || setCookies == null || setCookies.isEmpty()) {
            return 0;
        }
        Map<String, String> jar = load(c, platformId);
        int changed = 0;
        for (String raw : setCookies) {
            if (raw == null) {
                continue;
            }
            String[] parts = raw.split(";");
            if (parts.length == 0) {
                continue;
            }
            String pair = parts[0].trim();
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = pair.substring(0, idx).trim();
            String value = pair.substring(idx + 1).trim();
            if (name.length() == 0) {
                continue;
            }
            boolean expired = false;
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim().toLowerCase();
                if (attr.startsWith("max-age=")) {
                    String v = attr.substring(8).trim();
                    try {
                        if (Long.parseLong(v) <= 0) {
                            expired = true;
                        }
                    } catch (Exception ignored) {
                    }
                } else if (attr.startsWith("expires=") && (attr.contains("1970") || attr.contains("1969"))) {
                    expired = true;
                }
            }
            if (expired) {
                if (jar.remove(name) != null) {
                    changed++;
                }
            } else {
                String old = jar.put(name, value);
                if (old == null || !old.equals(value)) {
                    changed++;
                }
            }
        }
        save(c, platformId, jar);
        return changed;
    }

    public static int size(Context c, String platformId) {
        return load(c, platformId).size();
    }

    public static void clear(Context c, String platformId) {
        if (c == null || platformId == null) {
            return;
        }
        sp(c).edit().remove(K + platformId).apply();
    }

    public static void clearAll(Context c) {
        if (c == null) {
            return;
        }
        sp(c).edit().clear().apply();
    }

    private static void save(Context c, String platformId, Map<String, String> jar) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            sp(c).edit().putString(K + platformId, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
