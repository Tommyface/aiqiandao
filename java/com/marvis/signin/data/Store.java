package com.marvis.signin.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.marvis.signin.model.Platform;
import com.marvis.signin.model.Record;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全部数据只落在本机 SharedPreferences，不联网、不上传。
 */
public class Store {

    private static final String PREF = "signin_store_v1";
    private static final String K_PLATFORMS = "platforms";
    private static final String K_RECORDS = "records";
    private static final String K_HOUR = "hour";
    private static final String K_MINUTE = "minute";
    private static final String K_GUARD = "guard";
    private static final int MAX_RECORDS = 300;

    private final SharedPreferences sp;

    /** 全局 Application Context，供网络层写抓包记录、读写 Cookie 罐。 */
    public static Context APP;

    public Store(Context c) {
        APP = c.getApplicationContext();
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- 平台 ----------------

    public List<Platform> getPlatforms() {
        List<Platform> list = new ArrayList<Platform>();
        String raw = sp.getString(K_PLATFORMS, "");
        if (raw == null || raw.length() == 0) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Platform.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void savePlatforms(List<Platform> platforms) {
        JSONArray arr = new JSONArray();
        for (Platform p : platforms) {
            arr.put(p.toJson());
        }
        sp.edit().putString(K_PLATFORMS, arr.toString()).apply();
    }

    public Platform find(String id) {
        if (id == null) {
            return null;
        }
        for (Platform p : getPlatforms()) {
            if (id.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    public void upsert(Platform p) {
        List<Platform> list = getPlatforms();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(p.id)) {
                list.set(i, p);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(p);
        }
        savePlatforms(list);
    }

    public void remove(String id) {
        List<Platform> list = getPlatforms();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        savePlatforms(list);
    }

    public boolean isEmpty() {
        return getPlatforms().isEmpty();
    }

    // ---------------- 时间 / 开关 ----------------

    public int getHour() {
        return sp.getInt(K_HOUR, 8);
    }

    public int getMinute() {
        return sp.getInt(K_MINUTE, 0);
    }

    public void setTime(int hour, int minute) {
        sp.edit().putInt(K_HOUR, hour).putInt(K_MINUTE, minute).apply();
    }

    public boolean isGuardOn() {
        return sp.getBoolean(K_GUARD, false);
    }

    public void setGuardOn(boolean on) {
        sp.edit().putBoolean(K_GUARD, on).apply();
    }

    // ---------------- 记录 ----------------

    public void addRecord(Record r) {
        List<Record> list = getRecords();
        list.add(0, r);
        while (list.size() > MAX_RECORDS) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (Record item : list) {
            arr.put(item.toJson());
        }
        sp.edit().putString(K_RECORDS, arr.toString()).apply();
    }

    public List<Record> getRecords() {
        List<Record> list = new ArrayList<Record>();
        String raw = sp.getString(K_RECORDS, "");
        if (raw == null || raw.length() == 0) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Record.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 某平台最近一条记录，没有则 null。 */
    public Record lastRecord(String platformId) {
        for (Record r : getRecords()) {
            if (platformId.equals(r.platformId)) {
                return r;
            }
        }
        return null;
    }

    /** 今天已成功签到的平台 id 集合。 */
    public Set<String> todaySuccessIds() {
        Set<String> ids = new HashSet<String>();
        long todayStart = com.marvis.signin.util.TimeUtils.startOfToday();
        for (Record r : getRecords()) {
            if (r.success && r.time >= todayStart) {
                ids.add(r.platformId);
            }
        }
        return ids;
    }

    /** 今天该平台最近一次成功时间，0 表示今天还没成功。 */
    public long todaySuccessTime(String platformId) {
        long todayStart = com.marvis.signin.util.TimeUtils.startOfToday();
        for (Record r : getRecords()) {
            if (r.success && platformId.equals(r.platformId) && r.time >= todayStart) {
                return r.time;
            }
        }
        return 0L;
    }
}
