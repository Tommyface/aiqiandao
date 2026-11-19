package com.marvis.signin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.model.Record;
import com.marvis.signin.net.SignEngine;
import com.marvis.signin.service.KeepAliveService;
import com.marvis.signin.service.ScheduleManager;
import com.marvis.signin.util.Notify;
import com.marvis.signin.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private Store store;
    private SharedPreferences prefs;

    private LinearLayout container;
    private TextView tvSummary;
    private TextView tvNext;
    private TextView tvTime;
    private Switch switchGuard;

    private final Set<String> runningIds = new HashSet<String>();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new Store(this);
        prefs = getSharedPreferences("signin_ui", MODE_PRIVATE);
        Notify.ensureChannels(this);

        container = (LinearLayout) findViewById(R.id.platforms_container);
        tvSummary = (TextView) findViewById(R.id.tv_summary);
        tvNext = (TextView) findViewById(R.id.tv_next);
        tvTime = (TextView) findViewById(R.id.tv_time);
        switchGuard = (Switch) findViewById(R.id.switch_guard);

        seedDefaultsIfEmpty();

        findViewById(R.id.btn_time).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime();
            }
        });
        findViewById(R.id.btn_sign_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signAllNow();
            }
        });
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PlatformEditActivity.class));
            }
        });
        findViewById(R.id.btn_trace).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TraceActivity.class));
            }
        });

        findViewById(R.id.btn_logs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogs();
            }
        });

        switchGuard.setChecked(store.isGuardOn());
        switchGuard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                store.setGuardOn(isChecked);
                if (isChecked) {
                    startGuard();
                    scheduleNext();
                    askBatteryWhitelist();
                } else {
                    stopService(new Intent(MainActivity.this, KeepAliveService.class));
                    Toast.makeText(MainActivity.this, "已关闭守护，定时签到仍会在系统允许时执行",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                }
            }
        });

        requestNotificationPermission();
        updateTimeViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        maybeAutoSign();
    }

    // ---------------- 数据初始化 ----------------

    /** 首次进入预置七个平台的真实接口模板；凭据（token / Cookie）留空由用户填写。 */
    private void seedDefaultsIfEmpty() {
        List<Platform> builtin = buildBuiltinTemplates();
        if (store.isEmpty()) {
            store.savePlatforms(builtin);
            prefs.edit().putBoolean("minimax_seeded", true)
                    .putBoolean("trae_seeded", true)
                    .putBoolean("dumate_seeded", true)
                    .putBoolean("teleagent_seeded", true).apply();
            return;
        }
        // 老版本升级：
        // 1) URL 仍为空的同名平台 -> 整条套用内置模板
        // 2) URL 已填但前置请求为空的同名平台 -> 只补 preUrl（老版本没有该字段），不动用户已有配置
        List<Platform> cur = store.getPlatforms();
        boolean changed = false;
        for (Platform t : builtin) {
            if (t.name == null) continue;
            for (Platform c : cur) {
                if (c.name == null || !c.name.trim().equalsIgnoreCase(t.name.trim())) continue;
                boolean emptyUrl = c.url == null || c.url.trim().length() == 0;
                if (emptyUrl) {
                    c.url = t.url;
                    c.method = t.method;
                    c.headers = t.headers;
                    c.body = t.body;
                    c.keyword = t.keyword;
                    c.doneKeyword = t.doneKeyword;
                    c.preUrl = t.preUrl;
                    c.kind = t.kind; // MiniMax 这类专用流程必须带上，否则会走通用模板
                    changed = true;
                } else if (c.preUrl == null || c.preUrl.trim().length() == 0) {
                    c.preUrl = t.preUrl;
                    changed = true;
                }
            }
        }
        // 新增平台（MiniMax）：老版本升级时补一条；只补一次，用户之后手动删掉就不再自动加回
        if (!prefs.getBoolean("minimax_seeded", false)) {
            if (ensureMinimaxPlatform(cur)) {
                changed = true;
            }
            prefs.edit().putBoolean("minimax_seeded", true).apply();
        }
        // 新增平台（Trae CN）：同样只补一次
        if (!prefs.getBoolean("trae_seeded", false)) {
            if (ensureTraePlatform(cur)) {
                changed = true;
            }
            prefs.edit().putBoolean("trae_seeded", true).apply();
        }
        // 新增/升级平台（百度搭子）：老版本的通用模板原地升级为桌面端专用流程，只补一次
        if (!prefs.getBoolean("dumate_seeded", false)) {
            if (ensureDumatePlatform(cur)) {
                changed = true;
            }
            prefs.edit().putBoolean("dumate_seeded", true).apply();
        }
        // 新增平台（TeleAgent 积分福利社）：v1.7.0 升级时补一条，只补一次
        if (!prefs.getBoolean("teleagent_seeded", false)) {
            if (ensureTeleagentPlatform(cur)) {
                changed = true;
            }
            prefs.edit().putBoolean("teleagent_seeded", true).apply();
        }
        if (changed) {
            store.savePlatforms(cur);
        }
    }

    /** 列表里没有 MiniMax 平台时补一条，返回是否真的补了。 */
    private boolean ensureMinimaxPlatform(List<Platform> cur) {
        for (Platform c : cur) {
            if (c.kind != null && "minimax".equalsIgnoreCase(c.kind.trim())) {
                return false;
            }
        }
        cur.add(buildMinimaxTemplate());
        return true;
    }

    /**
     * MiniMax Code（海螺）模板。
     * 这条平台走专用 OAuth 流程（设备码授权 + 令牌自动轮换 + status→claim 两步签到），
     * 下面的 URL / 请求头只用于列表展示，实际请求由 Minimax 协议层发起。
     */
    private Platform buildMinimaxTemplate() {
        Platform mm = Platform.create("MiniMax Code",
                "https://agent.minimax.cn/minimax-cloud/api/v1/signin/claim?timezone_id=Asia/Shanghai");
        mm.kind = "minimax";
        mm.method = "POST";
        mm.headers = "Authorization: Bearer {{token}}\n"
                + "Content-Type: application/json\n"
                + "Accept: application/json";
        mm.body = "{}";
        mm.keyword = "claim_result";
        mm.doneKeyword = "\"claim_result\":2";
        mm.preUrl = "https://agent.minimax.cn/minimax-cloud/api/v1/signin/status?timezone_id=Asia/Shanghai";
        mm.credValidHours = 1; // access token 约 1 小时，由 refresh token 自动续期
        // 默认先停用：授权成功后再自动打开，避免没授权时每天把定时任务刷成失败
        mm.enabled = false;
        return mm;
    }

    /** 列表里没有 Trae 平台时补一条，返回是否真的补了。 */
    private boolean ensureTraePlatform(List<Platform> cur) {
        for (Platform c : cur) {
            if (c.kind != null && "trae".equalsIgnoreCase(c.kind.trim())) {
                return false;
            }
        }
        cur.add(buildTraeTemplate());
        return true;
    }

    /**
     * Trae CN（字节 Trae IDE）模板。
     * 走专用流程：在配置页粘贴 storage.json 解锁 token 与设备号，签到由 TraeSign 发起 status→claim 两步。
     * 下面的 URL / 请求头只用于列表展示。
     */
    private Platform buildTraeTemplate() {
        Platform tr = Platform.create("Trae CN",
                "https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim");
        tr.kind = "trae";
        tr.method = "POST";
        tr.headers = "Authorization: Cloud-IDE-JWT {{token}}\n"
                + "x-device-id: {{device}}\n"
                + "Content-Type: application/json\n"
                + "Accept: application/json";
        tr.body = "{}";
        tr.keyword = "";
        tr.doneKeyword = "\"checked_in\":true";
        tr.preUrl = "https://api.trae.cn/trae/api/v2/ug/checkin_credits/status";
        tr.credValidHours = 14 * 24; // 访问令牌约 14 天，由客户端续期后重新导入
        // 默认先停用：导入登录态后再自动打开，避免没导入时每天把定时任务刷成失败
        tr.enabled = false;
        return tr;
    }

    /**
     * 百度搭子：老版本的「百度搭子」通用模板原地升级为 kind=dumate，
     * 保留用户已经填好的 Cookie / 名称 / 开关，只补桌面端标识与标准地址。
     */
    private boolean ensureDumatePlatform(List<Platform> cur) {
        for (Platform c : cur) {
            if (c.isDumate()) {
                return false;
            }
        }
        for (Platform c : cur) {
            String name = c.name == null ? "" : c.name;
            String url = c.url == null ? "" : c.url;
            if (!name.contains("百度搭子") && !url.contains("/api/dumate/points/")) {
                continue;
            }
            c.kind = "dumate";
            if (url.length() == 0 || url.contains("console.bce.baidu.com")) {
                c.url = "https://dumate.baidu.com/api/dumate/points/loginBonus";
            }
            if (c.preUrl == null || c.preUrl.trim().length() == 0
                    || c.preUrl.contains("console.bce.baidu.com")) {
                c.preUrl = "https://dumate.baidu.com/api/dumate/points/loginBonusInfo";
            }
            c.method = "POST";
            c.body = "{}";
            if (c.headers == null || c.headers.trim().length() == 0
                    || c.headers.indexOf("X-Dumate-Client-Type") < 0) {
                String base = c.headers == null ? "" : c.headers.trim();
                if (base.length() > 0 && !base.endsWith("\n")) {
                    base = base + "\n";
                }
                c.headers = base + "X-Dumate-Client-Type: desktop";
            }
            // 没填 Cookie 的旧模板先停用，避免每天刷失败；填了 Cookie 的保持原开关
            if (!c.hasDumateCookie()) {
                c.enabled = false;
            }
            return true;
        }
        cur.add(buildDumateTemplate());
        return true;
    }

    /**
     * 百度搭子（DuMate）桌面端模板。
     * 走专用流程：网页版 Cookie + X-Dumate-Client-Type: desktop，桌面端可领 500 积分
     * （网页已签 200 时补 300）。下面的 URL / 请求头用于列表展示与兜底。
     */
    private Platform buildDumateTemplate() {
        Platform dm = Platform.create("百度搭子",
                "https://dumate.baidu.com/api/dumate/points/loginBonus");
        dm.kind = "dumate";
        dm.method = "POST";
        dm.headers = "X-Dumate-Client-Type: desktop\n"
                + "Cookie: {{token}}\n"
                + "csrfToken: {{cookie:bce-user-info}}\n"
                + "Content-Type: application/json\n"
                + "Origin: https://dumate.baidu.com\n"
                + "Referer: https://dumate.baidu.com/app";
        dm.body = "{}";
        dm.keyword = "success|已签到|已领取";
        dm.doneKeyword = "已签到|已领取";
        dm.preUrl = "https://dumate.baidu.com/api/dumate/points/loginBonusInfo";
        // 默认停用：粘贴 Cookie 后自动启用，避免没配凭据时每天把定时任务刷成失败
        dm.enabled = false;
        return dm;
    }

    /** 列表里没有 TeleAgent 平台时补一条，返回是否真的补了。 */
    private boolean ensureTeleagentPlatform(List<Platform> cur) {
        for (Platform c : cur) {
            if (c.isTeleagent()) {
                return false;
            }
        }
        cur.add(buildTeleAgentTemplate());
        return true;
    }

    /**
     * 星辰超级智能体 TeleAgent（中国电信）模板。
     * 走专用流程：在配置页粘贴福利社网页的 operationToken（即接口的 X-Token），
     * 签到由 TeleAgentSign 发起：查每日任务 → 签到 → 顺带领取其余可领的福利活动。
     * 下面的 URL / 请求头只用于列表展示与兜底。
     */
    private Platform buildTeleAgentTemplate() {
        Platform ta = Platform.create("TeleAgent",
                "https://agent.teleai.com.cn/superCowork/sapi/user/portal/companyStore/v3/checkDayTask");
        ta.kind = "teleagent";
        ta.method = "GET";
        ta.headers = "X-Token: {{token}}\n"
                + "X-App-Version: 3.1.0\n"
                + "X-OS-Type: windows\n"
                + "X-SuperAgent-Device-Id: {{device}}\n"
                + "Accept: application/json, text/plain, */*";
        ta.body = "";
        ta.keyword = "activity_data";
        ta.doneKeyword = "";
        ta.preUrl = "";
        ta.credValidHours = 0; // 令牌是 JWT 时自动读 exp，不是则不跟踪有效期
        // 默认停用：粘贴令牌后自动启用，避免没配凭据时每天把定时任务刷成失败
        ta.enabled = false;
        return ta;
    }

    /** 内置平台的接口模板（来源：公开签到脚本抓包，凭据需用户自填）。 */
    private List<Platform> buildBuiltinTemplates() {
        List<Platform> list = new ArrayList<Platform>();

        Platform wb = Platform.create("workbuddy", "https://www.workbuddy.cn/v2/billing/meter/daily-checkin");
        wb.method = "POST";
        wb.headers = "Authorization: Bearer {{token}}\n"
                + "X-User-Id: 填你的uid\n"
                + "Content-Type: application/json\n"
                + "Accept: application/json\n"
                + "User-Agent: WorkBuddy";
        wb.body = "{}";
        wb.keyword = "credit|今天已签到";
        wb.doneKeyword = "今天已签到";
        wb.preUrl = "https://www.workbuddy.cn/v2/billing/meter/checkin-activity-status";
        wb.credValidHours = 30 * 24; // access token 约 30 天
        list.add(wb);

        // 百度搭子：专用流程，桌面端 500 积分（网页已签则补 300）
        list.add(buildDumateTemplate());

        Platform qd = Platform.create("Qoder",
                "https://openapi.qoder.com.cn/sash/api/v1/me/campaigns/"
                        + "{{pre:$.campaigns[actionType=CLAIM_BENEFIT].campaignId}}/claim");
        qd.method = "POST";
        qd.headers = "Authorization: Bearer {{token}}\n"
                + "Cosy-ClientType: 10\n"
                + "Accept: application/json\n"
                + "User-Agent: Qoder/claim";
        qd.body = "";
        qd.keyword = "CLAIMED|claimed|success";
        // 已领取时接口返回 "replayed":true，按「已签到」处理
        qd.doneKeyword = "\"replayed\":true";
        qd.preUrl = "https://openapi.qoder.com.cn/sash/api/v1/me/campaigns";
        qd.credValidHours = 16 * 24; // 客户端登录态 token 约 16 天
        list.add(qd);

        Platform ap = Platform.create("AIPY", "https://www.trustoken.cn/api/user/checkin");
        ap.method = "POST";
        ap.headers = "Authorization: Bearer {{token}}\n"
                + "New-Api-User: 填你的uid\n"
                + "Content-Type: application/json\n"
                + "Accept: application/json";
        ap.body = "";
        ap.keyword = "success|已签到";
        ap.doneKeyword = "已签到";
        ap.preUrl = "https://www.trustoken.cn/api/user/checkin/status";
        list.add(ap);

        // MiniMax Code（海螺）：专用 OAuth 流程，凭据靠 App 内授权获取，不用手填
        list.add(buildMinimaxTemplate());

        // Trae CN：专用流程，粘贴 storage.json 在本机解密登录态
        list.add(buildTraeTemplate());

        // TeleAgent：专用流程，粘贴福利社网页 operationToken，签到 + 福利活动一次领完
        list.add(buildTeleAgentTemplate());

        return list;
    }

    private List<Platform> buildTargets() {
        List<Platform> targets = new ArrayList<Platform>();
        for (Platform p : store.getPlatforms()) {
            if (p.enabled && p.url != null && p.url.trim().length() > 0) {
                targets.add(p);
            }
        }
        return targets;
    }

    // ---------------- 界面刷新 ----------------

    private void updateTimeViews() {
        tvTime.setText(TimeUtils.two(store.getHour()) + ":" + TimeUtils.two(store.getMinute()));
        long next = TimeUtils.nextTrigger(store.getHour(), store.getMinute());
        String day = TimeUtils.isSameDay(next, System.currentTimeMillis()) ? "今天" : "明天";
        String guard = store.isGuardOn() ? "" : " · 守护未开启";
        tvNext.setText("下次签到 " + day + " " + TimeUtils.hhmm(next) + guard);
    }

    private void refresh() {
        List<Platform> platforms = store.getPlatforms();
        Set<String> done = store.todaySuccessIds();
        List<Platform> targets = buildTargets();

        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        int okToday = 0;
        for (Platform p : targets) {
            if (done.contains(p.id)) {
                okToday++;
            }
        }
        tvSummary.setText("今日 " + okToday + "/" + targets.size() + " 已签到");
        updateTimeViews();

        if (platforms.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有平台，点右上角「+ 添加平台」开始");
            empty.setTextColor(getResources().getColor(R.color.text_hint));
            empty.setTextSize(13);
            empty.setPadding(8, 32, 8, 32);
            container.addView(empty);
            return;
        }

        for (int i = 0; i < platforms.size(); i++) {
            final Platform p = platforms.get(i);
            View item = inflater.inflate(R.layout.item_platform, container, false);
            TextView tvIcon = (TextView) item.findViewById(R.id.tv_icon);
            TextView tvName = (TextView) item.findViewById(R.id.tv_name);
            TextView tvUrl = (TextView) item.findViewById(R.id.tv_url);
            TextView tvStatus = (TextView) item.findViewById(R.id.tv_status);
            TextView tvLast = (TextView) item.findViewById(R.id.tv_last);
            TextView tvCred = (TextView) item.findViewById(R.id.tv_cred);

            tvIcon.setText(p.initial());
            tvName.setText(p.name);

            boolean configured = p.url != null && p.url.trim().length() > 0;
            tvUrl.setText(configured ? p.url : "未填写接口地址 · 点击配置");

            Record last = store.lastRecord(p.id);
            String lastText;
            if (last == null) {
                lastText = "尚未签到";
            } else {
                lastText = "上次 " + TimeUtils.full(last.time) + " · " + last.message;
            }

            if (runningIds.contains(p.id)) {
                setStatus(tvStatus, "签到中", R.color.run, R.drawable.tag_run);
                lastText = "正在发起签到请求…";
            } else if (p.isMinimax() && !p.hasMinimaxToken()) {
                setStatus(tvStatus, "未授权", R.color.fail, R.drawable.tag_fail);
                lastText = "点「授权 MiniMax」登录一次，之后可自动签到";
            } else if (p.isTrae() && !p.hasTraeToken()) {
                setStatus(tvStatus, "未导入", R.color.fail, R.drawable.tag_fail);
                lastText = "点进配置页粘贴 Trae 的 storage.json，导入登录态";
            } else if (p.isDumate() && !p.hasDumateCookie()) {
                setStatus(tvStatus, "未配置", R.color.fail, R.drawable.tag_fail);
                lastText = "点进配置页粘贴网页版 Cookie，桌面端签到可领 500 积分";
            } else if (p.isTeleagent() && !p.hasTeleagentToken()) {
                setStatus(tvStatus, "未导入", R.color.fail, R.drawable.tag_fail);
                lastText = "点进配置页粘贴福利社网页的 operationToken，签到 + 福利活动一次领完";
            } else if (!p.enabled) {
                setStatus(tvStatus, "已停用", R.color.wait, R.drawable.tag_wait);
            } else if (!configured) {
                setStatus(tvStatus, "待配置", R.color.wait, R.drawable.tag_wait);
            } else if (store.todaySuccessTime(p.id) > 0) {
                setStatus(tvStatus, "已签到", R.color.ok, R.drawable.tag_ok);
                lastText = "今日 " + TimeUtils.hhmm(store.todaySuccessTime(p.id)) + " 签到成功";
            } else if (last != null && !last.success && TimeUtils.isSameDay(last.time, System.currentTimeMillis())) {
                setStatus(tvStatus, "失败", R.color.fail, R.drawable.tag_fail);
            } else {
                setStatus(tvStatus, "待签到", R.color.wait, R.drawable.tag_wait);
            }
            tvLast.setText(lastText);

            // 凭据有效期（JWT 的 exp 或用户填写的有效天数），拿不到就不显示
            String credLabel = com.marvis.signin.util.Cred.label(p);
            if (credLabel.length() > 0) {
                tvCred.setVisibility(View.VISIBLE);
                tvCred.setText(credLabel);
                tvCred.setTextColor(getResources().getColor(
                        com.marvis.signin.util.Cred.expired(p) ? R.color.fail : R.color.text_hint));
            } else {
                tvCred.setVisibility(View.GONE);
            }

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent it = new Intent(MainActivity.this, PlatformEditActivity.class);
                    it.putExtra("id", p.id);
                    startActivity(it);
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    signSingle(p);
                    return true;
                }
            });

            // 条目上的「签到」按钮：点一下立刻给这个平台签到
            TextView btnSign = (TextView) item.findViewById(R.id.btn_sign);
            if (btnSign != null) {
                btnSign.setEnabled(!runningIds.contains(p.id));
                btnSign.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        signSingle(p);
                    }
                });
            }
            container.addView(item);
        }
    }

    private void setStatus(TextView tv, String text, int colorRes, int bgRes) {
        tv.setText(text);
        tv.setTextColor(getResources().getColor(colorRes));
        tv.setBackgroundResource(bgRes);
    }

    // ---------------- 操作 ----------------

    private void pickTime() {
        TimePickerDialog dialog = new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                store.setTime(hourOfDay, minute);
                scheduleNext();
                updateTimeViews();
                Toast.makeText(MainActivity.this,
                        "已设为每天 " + TimeUtils.two(hourOfDay) + ":" + TimeUtils.two(minute) + " 签到",
                        Toast.LENGTH_SHORT).show();
            }
        }, store.getHour(), store.getMinute(), true);
        dialog.show();
    }

    private void scheduleNext() {
        ScheduleManager.schedule(this);
        if (store.isGuardOn()) {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
    }

    private void signAllNow() {
        final List<Platform> targets = buildTargets();
        if (targets.isEmpty()) {
            Toast.makeText(this, "请先为至少一个平台填写签到接口", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(this, "正在签到，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        for (Platform p : targets) {
            runningIds.add(p.id);
        }
        refresh();

        SignEngine.signAll(this, targets, new SignEngine.Callback() {
            @Override
            public void onOne(Platform p, boolean ok, String message) {
                runningIds.remove(p.id);
                refresh();
            }

            @Override
            public void onAllDone(int success, int total) {
                busy = false;
                runningIds.clear();
                refresh();
                if (total > 0) {
                    String text = success == total
                            ? "全部 " + total + " 个平台签到成功"
                            : success + "/" + total + " 个平台签到成功";
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                    Notify.result(MainActivity.this, "AI签到助手", text);
                }
            }
        });
    }

    private void signSingle(final Platform p) {
        if (p.url == null || p.url.trim().length() == 0) {
            Toast.makeText(this, "先配置该平台的签到接口", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p.enabled) {
            Toast.makeText(this, "该平台已停用，可在配置页打开开关", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(this, "正在签到，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        runningIds.add(p.id);
        refresh();
        SignEngine.signOne(this, p, new SignEngine.Callback() {
            @Override
            public void onOne(Platform platform, boolean ok, String message) {
                runningIds.remove(platform.id);
                busy = false;
                refresh();
                Toast.makeText(MainActivity.this,
                        platform.name + (ok ? " 签到成功" : " 签到失败")
                                + ((platform.isMinimax() || platform.isTrae() || platform.isDumate()
                                || platform.isTeleagent()) ? " · " + message : ""),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAllDone(int success, int total) {
            }
        });
    }

    /** 打开 App 时补做当天漏签，每台设备每天最多自动补一次。 */
    private void maybeAutoSign() {
        if (busy) {
            return;
        }
        long last = prefs.getLong("last_auto_sign", 0L);
        if (last >= TimeUtils.startOfToday()) {
            return;
        }
        if (!TimeUtils.passedTodayAt(store.getHour(), store.getMinute())) {
            return;
        }
        List<Platform> targets = buildTargets();
        if (targets.isEmpty()) {
            return;
        }
        Set<String> done = store.todaySuccessIds();
        boolean pending = false;
        for (Platform p : targets) {
            if (!done.contains(p.id)) {
                pending = true;
                break;
            }
        }
        if (!pending) {
            return;
        }
        prefs.edit().putLong("last_auto_sign", System.currentTimeMillis()).apply();
        busy = true;
        for (Platform p : targets) {
            runningIds.add(p.id);
        }
        refresh();
        Toast.makeText(this, "检测到今日漏签，正在自动补签", Toast.LENGTH_SHORT).show();

        SignEngine.signPending(this, targets, new SignEngine.Callback() {
            @Override
            public void onOne(Platform p, boolean ok, String message) {
                runningIds.remove(p.id);
                refresh();
            }

            @Override
            public void onAllDone(int success, int total) {
                busy = false;
                runningIds.clear();
                refresh();
                if (total > 0) {
                    Notify.result(MainActivity.this, "AI签到助手 · 补签完成",
                            success + "/" + total + " 个平台签到成功");
                }
            }
        });
    }

    private void showLogs() {
        List<Record> records = store.getRecords();
        if (records.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("签到日志")
                    .setMessage("还没有签到记录").setPositiveButton("知道了", null).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(records.size(), 80);
        for (int i = 0; i < n; i++) {
            Record r = records.get(i);
            sb.append(TimeUtils.full(r.time)).append("  ").append(r.platformName)
                    .append(r.success ? "  ✓ 成功" : "  ✕ 失败").append("\n");
            sb.append("    ").append(r.message).append("\n\n");
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(getResources().getColor(R.color.text_main));
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("签到日志 · 最近 " + n + " 条")
                .setView(sv).setPositiveButton("关闭", null).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 权限 / 守护 ----------------

    private void startGuard() {
        Intent i = new Intent(this, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Toast.makeText(this, "签到守护已开启", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "守护启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            } catch (Exception ignored) {
            }
        }
    }

    private void askBatteryWhitelist() {
        if (prefs.getBoolean("asked_battery", false)) {
            return;
        }
        prefs.edit().putBoolean("asked_battery", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("让守护更稳定")
                .setMessage("部分机型会清理后台，导致准点签到失效。建议把本应用加入「电池优化白名单」并允许自启动。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }
}
