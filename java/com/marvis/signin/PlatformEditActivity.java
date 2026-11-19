package com.marvis.signin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.marvis.signin.data.Store;
import com.marvis.signin.model.Platform;
import com.marvis.signin.net.Dumate;
import com.marvis.signin.net.Minimax;
import com.marvis.signin.net.SignEngine;
import com.marvis.signin.net.Trae;
import com.marvis.signin.service.KeepAliveService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PlatformEditActivity extends Activity {

    private Store store;
    private Platform editing;
    private boolean isNew;

    private EditText etName;
    private EditText etUrl;
    private EditText etPreUrl;
    private EditText etHeaders;
    private EditText etBody;
    private EditText etKeyword;
    private EditText etDoneKeyword;
    private Spinner spMethod;
    private Switch swEnabled;
    private TextView tvTest;
    private EditText etCred;
    private EditText etCredDays;
    private TextView tvCredState;
    private EditText etRenewUrl;
    private EditText etRenewExtract;
    private EditText etRenewBody;
    private EditText etRenewHours;
    private Spinner spRenewMethod;

    // ---------------- MiniMax 授权 ----------------
    private LinearLayout llMinimax;
    private TextView tvMinimaxState;
    private TextView btnMinimaxAuth;
    private SharedPreferences authPrefs;

    // ---------------- Trae 登录态导入 ----------------
    private LinearLayout llTrae;
    private EditText etTraeStorage;
    private EditText etTraeDevice;
    private TextView tvTraeState;
    private TextView btnTraeImport;

    // ---------------- 百度搭子 Cookie 导入 ----------------
    private LinearLayout llDumate;
    private EditText etDumateCookie;
    private TextView tvDumateState;
    private TextView btnDumateSave;

    // ---------------- TeleAgent 令牌导入 ----------------
    private LinearLayout llTeleagent;
    private EditText etTeleagentToken;
    private EditText etTeleagentDevice;
    private TextView tvTeleagentState;
    private TextView btnTeleagentSave;

    // 授权会话是「跨 Activity 存活」的：轮询线程可能在页面重建时还在跑，
    // 用静态字段保证同一时刻只有一条轮询线程，结果也只交给当前可见的页面。
    private static volatile PlatformEditActivity sCurrent = null;
    private static volatile boolean sPolling = false;
    private static volatile Thread sPollThread = null;
    /** 本次授权是否是我们自己拉起的守护服务：只有自己拉起的才由我们停。 */
    private static volatile boolean sGuardStartedByUs = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);

        String id = getIntent().getStringExtra("id");
        editing = id == null ? null : store.find(id);
        isNew = editing == null;
        if (editing == null) {
            editing = Platform.create("", "");
        }

        setContentView(R.layout.activity_platform_edit);

        etName = (EditText) findViewById(R.id.et_name);
        etUrl = (EditText) findViewById(R.id.et_url);
        etPreUrl = (EditText) findViewById(R.id.et_pre_url);
        etHeaders = (EditText) findViewById(R.id.et_headers);
        etBody = (EditText) findViewById(R.id.et_body);
        etKeyword = (EditText) findViewById(R.id.et_keyword);
        etDoneKeyword = (EditText) findViewById(R.id.et_done_keyword);
        spMethod = (Spinner) findViewById(R.id.sp_method);
        swEnabled = (Switch) findViewById(R.id.switch_enabled);
        tvTest = (TextView) findViewById(R.id.tv_test_result);
        etCred = (EditText) findViewById(R.id.et_cred);
        etCredDays = (EditText) findViewById(R.id.et_cred_days);
        tvCredState = (TextView) findViewById(R.id.tv_cred_state);
        etRenewUrl = (EditText) findViewById(R.id.et_renew_url);
        etRenewExtract = (EditText) findViewById(R.id.et_renew_extract);
        etRenewBody = (EditText) findViewById(R.id.et_renew_body);
        etRenewHours = (EditText) findViewById(R.id.et_renew_hours);
        spRenewMethod = (Spinner) findViewById(R.id.sp_renew_method);

        ((TextView) findViewById(R.id.tv_title)).setText(isNew ? "添加平台" : "平台配置");

        String[] methods = new String[]{"POST", "GET"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, methods);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMethod.setAdapter(adapter);
        spMethod.setSelection("GET".equalsIgnoreCase(editing.method) ? 1 : 0);

        ArrayAdapter<String> renewAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, methods);
        renewAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRenewMethod.setAdapter(renewAdapter);
        spRenewMethod.setSelection("GET".equalsIgnoreCase(editing.renewMethod) ? 1 : 0);

        etName.setText(editing.name);
        etUrl.setText(editing.url);
        etPreUrl.setText(editing.preUrl);
        etHeaders.setText(editing.headers);
        etBody.setText(editing.body);
        etKeyword.setText(editing.keyword);
        etDoneKeyword.setText(editing.doneKeyword);
        swEnabled.setChecked(editing.enabled);

        etCred.setText(editing.credValue);
        etCredDays.setText(editing.credValidHours <= 0 ? ""
                : String.valueOf(Math.round(editing.credValidHours / 24f)));
        android.text.TextWatcher credWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateCredState();
            }
        };
        etCred.addTextChangedListener(credWatcher);
        etCredDays.addTextChangedListener(credWatcher);
        updateCredState();
        etRenewUrl.setText(editing.renewUrl);
        etRenewExtract.setText(editing.renewExtract);
        etRenewBody.setText(editing.renewBody);
        etRenewHours.setText(editing.renewEveryHours <= 0 ? "" : String.valueOf(editing.renewEveryHours));

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test();
            }
        });
        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        findViewById(R.id.btn_renew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testRenew();
            }
        });

        // MiniMax 专属区块：只有 MiniMax 平台才显示
        llMinimax = (LinearLayout) findViewById(R.id.ll_minimax);
        tvMinimaxState = (TextView) findViewById(R.id.tv_minimax_state);
        btnMinimaxAuth = (TextView) findViewById(R.id.btn_minimax_auth);
        authPrefs = getSharedPreferences("signin_auth", MODE_PRIVATE);
        if (editing.isMinimax()) {
            llMinimax.setVisibility(View.VISIBLE);
            updateMinimaxState();
            btnMinimaxAuth.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startMinimaxAuth();
                }
            });
        } else {
            llMinimax.setVisibility(View.GONE);
        }

        // Trae 专属区块：只有 Trae 平台才显示
        llTrae = (LinearLayout) findViewById(R.id.ll_trae);
        etTraeStorage = (EditText) findViewById(R.id.et_trae_storage);
        etTraeDevice = (EditText) findViewById(R.id.et_trae_device);
        tvTraeState = (TextView) findViewById(R.id.tv_trae_state);
        btnTraeImport = (TextView) findViewById(R.id.btn_trae_import);
        if (editing.isTrae()) {
            llTrae.setVisibility(View.VISIBLE);
            etTraeDevice.setText(editing.credDeviceId == null ? "" : editing.credDeviceId);
            updateTraeState();
            btnTraeImport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    importTrae();
                }
            });
        } else {
            llTrae.setVisibility(View.GONE);
        }

        // 百度搭子专属区块：只有 kind=dumate 的平台才显示
        llDumate = (LinearLayout) findViewById(R.id.ll_dumate);
        etDumateCookie = (EditText) findViewById(R.id.et_dumate_cookie);
        tvDumateState = (TextView) findViewById(R.id.tv_dumate_state);
        btnDumateSave = (TextView) findViewById(R.id.btn_dumate_save);
        if (editing.isDumate()) {
            llDumate.setVisibility(View.VISIBLE);
            etDumateCookie.setText(editing.credValue == null ? "" : editing.credValue);
            updateDumateState();
            btnDumateSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveDumateCookie();
                }
            });
        } else {
            llDumate.setVisibility(View.GONE);
        }

        // TeleAgent 专属区块：只有 kind=teleagent 的平台才显示
        llTeleagent = (LinearLayout) findViewById(R.id.ll_teleagent);
        etTeleagentToken = (EditText) findViewById(R.id.et_teleagent_token);
        etTeleagentDevice = (EditText) findViewById(R.id.et_teleagent_device);
        tvTeleagentState = (TextView) findViewById(R.id.tv_teleagent_state);
        btnTeleagentSave = (TextView) findViewById(R.id.btn_teleagent_save);
        if (editing.isTeleagent()) {
            llTeleagent.setVisibility(View.VISIBLE);
            etTeleagentDevice.setText(editing.credDeviceId == null ? "" : editing.credDeviceId);
            updateTeleagentState();
            btnTeleagentSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveTeleagentToken();
                }
            });
        } else {
            llTeleagent.setVisibility(View.GONE);
        }

        findViewById(R.id.btn_delete).setVisibility(isNew ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sCurrent = this;
        if (editing != null && editing.isMinimax()) {
            updateMinimaxState();
            resumePendingAuth();
        }
        if (editing != null && editing.isTrae()) {
            updateTraeState();
        }
        if (editing != null && editing.isDumate()) {
            updateDumateState();
        }
        if (editing != null && editing.isTeleagent()) {
            updateTeleagentState();
        }
    }

    @Override
    protected void onDestroy() {
        if (sCurrent == this) {
            sCurrent = null;
        }
        super.onDestroy();
    }

    private Platform collect() {
        Platform p = new Platform();
        p.id = editing.id;
        p.kind = editing.kind == null ? "" : editing.kind;
        String name = etName.getText().toString().trim();
        p.name = name.length() == 0
                ? (TextUtils.isEmpty(etUrl.getText().toString().trim()) ? "未命名平台" : hostOf(etUrl.getText().toString().trim()))
                : name;
        p.url = etUrl.getText().toString().trim();
        p.preUrl = etPreUrl.getText().toString().trim();
        p.method = spMethod.getSelectedItemPosition() == 1 ? "GET" : "POST";
        p.headers = etHeaders.getText().toString();
        p.body = etBody.getText().toString();
        p.keyword = etKeyword.getText().toString().trim();
        p.doneKeyword = etDoneKeyword.getText().toString().trim();
        p.enabled = swEnabled.isChecked();
        String newCred = etCred.getText().toString().trim();
        p.credValue = newCred;
        if (newCred.length() > 0 && (!newCred.equals(editing.credValue) || editing.credIssuedAt <= 0)) {
            // 新粘贴/修改的凭据：从这一刻开始算有效期
            p.credIssuedAt = System.currentTimeMillis();
            p.lastWarnAt = 0L;
        } else {
            p.credIssuedAt = editing.credIssuedAt;
            p.lastWarnAt = editing.lastWarnAt;
        }
        p.credValidHours = parseDays() * 24;
        p.renewUrl = etRenewUrl.getText().toString().trim();
        p.renewMethod = spRenewMethod.getSelectedItemPosition() == 1 ? "GET" : "POST";
        p.renewExtract = etRenewExtract.getText().toString().trim();
        p.renewBody = etRenewBody.getText().toString();
        p.lastRenewAt = editing.lastRenewAt;
        String hours = etRenewHours.getText().toString().trim();
        try {
            p.renewEveryHours = hours.length() == 0 ? 0 : Integer.parseInt(hours);
        } catch (Exception e) {
            p.renewEveryHours = 0;
        }
        if (p.renewEveryHours < 0) {
            p.renewEveryHours = 0;
        }
        if (editing.isMinimax()) {
            // MiniMax 的令牌由授权流程管理（access + refresh 成对保存），不受手工编辑影响。
            // 关键：refresh token 是一次性的，上游每次刷新都会换发。
            // 如果这里用 editing 里的旧快照回写，会把已经被上游消费掉的旧 RT 写回去，
            // 导致「授权一次长期可用」直接失效（下一轮续期 invalid_grant）。
            // 所以一律从存储里取最新值。
            Platform live = store.find(editing.id);
            p.kind = "minimax";
            p.credValue = live != null ? live.credValue : editing.credValue;
            p.credRefresh = live != null ? live.credRefresh : editing.credRefresh;
            p.credExpireAt = live != null ? live.credExpireAt : editing.credExpireAt;
            p.credIssuedAt = live != null ? live.credIssuedAt : editing.credIssuedAt;
            p.credValidHours = editing.credValidHours;
            p.renewUrl = "";
            p.renewMethod = "POST";
            p.renewHeaders = "";
            p.renewBody = "";
            p.renewExtract = "";
            p.renewEveryHours = 0;
            if (p.url == null || p.url.trim().length() == 0) {
                p.url = "https://agent.minimax.cn/minimax-cloud/api/v1/signin/claim?timezone_id=Asia/Shanghai";
            }
        }
        if (editing.isTrae()) {
            // Trae 的 token / 设备号由导入流程管理（解密 storage.json），这里只保留最新值。
            p.kind = "trae";
            p.credDeviceId = etTraeDevice == null ? editing.credDeviceId
                    : etTraeDevice.getText().toString().trim();
            p.credExpireAt = editing.credExpireAt;
            p.credValidHours = editing.credValidHours;
            p.credRefresh = "";
            p.renewUrl = "";
            p.renewMethod = "POST";
            p.renewHeaders = "";
            p.renewBody = "";
            p.renewExtract = "";
            p.renewEveryHours = 0;
            if (p.url == null || p.url.trim().length() == 0) {
                p.url = "https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim";
            }
        }
        if (editing.isDumate()) {
            // 百度搭子的 Cookie 由专属区块维护；这里保证走专用流程时字段不被通用逻辑覆盖。
            p.kind = "dumate";
            String dmCookie = etDumateCookie == null ? ""
                    : etDumateCookie.getText().toString().trim();
            if (dmCookie.length() > 0) {
                p.credValue = dmCookie;
            }
            p.credRefresh = "";
            p.renewUrl = "";
            p.renewMethod = "POST";
            p.renewHeaders = "";
            p.renewBody = "";
            p.renewExtract = "";
            p.renewEveryHours = 0;
            p.method = "POST";
            if (p.body == null || p.body.trim().length() == 0) {
                p.body = "{}";
            }
            if (p.url == null || p.url.trim().length() == 0) {
                p.url = "https://dumate.baidu.com/api/dumate/points/loginBonus";
            }
            if (p.preUrl == null || p.preUrl.trim().length() == 0) {
                p.preUrl = "https://dumate.baidu.com/api/dumate/points/loginBonusInfo";
            }
        }
        if (editing.isTeleagent()) {
            // TeleAgent 的令牌 / 设备号由专属区块维护；保证走专用流程时字段不被通用逻辑覆盖。
            p.kind = "teleagent";
            String taToken = etTeleagentToken == null ? ""
                    : com.marvis.signin.net.TeleAgent.normalize(
                    etTeleagentToken.getText().toString());
            if (taToken.length() > 0) {
                p.credValue = taToken;
            }
            String dev = etTeleagentDevice == null ? ""
                    : etTeleagentDevice.getText().toString().trim();
            p.credDeviceId = dev.length() > 0 ? dev : editing.credDeviceId;
            p.credRefresh = "";
            p.renewUrl = "";
            p.renewMethod = "GET";
            p.renewHeaders = "";
            p.renewBody = "";
            p.renewExtract = "";
            p.renewEveryHours = 0;
            p.method = "GET";
            p.body = "";
            if (p.url == null || p.url.trim().length() == 0) {
                p.url = "https://agent.teleai.com.cn/superCowork/sapi/user/portal/companyStore/v3/checkDayTask";
            }
        }
        return p;
    }

    /** 有效期天数输入，非法值按 0（不跟踪）处理。 */
    private int parseDays() {
        String s = etCredDays.getText().toString().trim();
        if (s.length() == 0) {
            return 0;
        }
        int d;
        try {
            d = Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
        if (d < 0) {
            d = 0;
        }
        return d > 3650 ? 3650 : d;
    }

    /** 实时提示凭据到期情况：JWT 直接读 exp，否则按填入时间 + 有效天数推算。 */
    private void updateCredState() {
        if (tvCredState == null || etCred == null || etCredDays == null) {
            return;
        }
        String cred = etCred.getText().toString().trim();
        if (cred.length() == 0) {
            tvCredState.setTextColor(getResources().getColor(R.color.text_hint));
            tvCredState.setText("尚未填写凭据，无法判断到期时间");
            return;
        }
        Platform tmp = new Platform();
        tmp.credValue = cred;
        tmp.credValidHours = parseDays() * 24;
        tmp.credIssuedAt = (cred.equals(editing.credValue) && editing.credIssuedAt > 0)
                ? editing.credIssuedAt : System.currentTimeMillis();
        // Trae 的到期时间来自 storage.json 的 expiredAt，优先展示它而不是 JWT 推算值
        if (editing != null && editing.isTrae()) {
            tmp.kind = "trae";
            tmp.credExpireAt = editing.credExpireAt;
        }
        long exp = com.marvis.signin.util.Cred.expiryMs(tmp);
        if (exp <= 0) {
            tvCredState.setTextColor(getResources().getColor(R.color.text_hint));
            tvCredState.setText("未识别有效期，不做到期提醒（若 token 是 JWT 会自动读 exp）");
            return;
        }
        boolean isJwt = com.marvis.signin.util.Cred.jwtExpMs(cred) > 0;
        boolean expired = exp <= System.currentTimeMillis();
        tvCredState.setTextColor(getResources().getColor(expired ? R.color.fail : R.color.text_sub));
        tvCredState.setText((isJwt ? "JWT 到期：" : "推算到期：")
                + com.marvis.signin.util.Cred.expiryText(tmp)
                + " · " + com.marvis.signin.util.Cred.label(tmp));
    }

    private String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null ? "未命名平台" : h;
        } catch (Exception e) {
            return "未命名平台";
        }
    }

    private boolean validate(Platform p, boolean needUrl) {
        if (needUrl) {
            if (p.url.length() == 0) {
                Toast.makeText(this, "请填写签到接口 URL", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!p.url.startsWith("http://") && !p.url.startsWith("https://")) {
                Toast.makeText(this, "URL 需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }

    private void save() {
        Platform p = collect();
        if (!validate(p, true)) {
            return;
        }
        if (p.name.trim().length() == 0) {
            Toast.makeText(this, "请填写平台名称", Toast.LENGTH_SHORT).show();
            return;
        }
        store.upsert(p);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void test() {
        Platform p = collect();
        if (!validate(p, true)) {
            return;
        }
        tvTest.setVisibility(View.VISIBLE);
        tvTest.setTextColor(getResources().getColor(R.color.text_sub));
        tvTest.setText("正在请求 " + p.url + " …");
        store.upsert(p);

        SignEngine.signOne(this, p, new SignEngine.Callback() {
            @Override
            public void onOne(Platform platform, boolean ok, String message) {
                tvTest.setVisibility(View.VISIBLE);
                tvTest.setTextColor(getResources().getColor(ok ? R.color.ok : R.color.fail));
                tvTest.setText((ok ? "签到成功 · " : "签到失败 · ") + message);
            }

            @Override
            public void onAllDone(int success, int total) {
            }
        });
    }

    /** 单独测一次续期接口，看能不能拿到新凭据。 */
    private void testRenew() {
        final Platform p = collect();
        if (!validate(p, true)) {
            return;
        }
        if (!p.hasRenew()) {
            Toast.makeText(this, "请先填写续期 URL 和提取路径", Toast.LENGTH_SHORT).show();
            return;
        }
        tvTest.setVisibility(View.VISIBLE);
        tvTest.setTextColor(getResources().getColor(R.color.text_sub));
        tvTest.setText("正在请求续期接口 " + p.renewUrl + " …");
        store.upsert(p);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SignEngine.renew(p);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvTest.setVisibility(View.VISIBLE);
                        tvTest.setTextColor(getResources().getColor(ok ? R.color.ok : R.color.fail));
                        if (ok) {
                            tvTest.setText("续期成功，凭据已更新并保存（长度 " + p.credValue.length() + "）");
                            etCred.setText(p.credValue);
                        } else {
                            tvTest.setText("续期失败，检查续期 URL / 提取路径 / 请求头是否正确");
                        }
                    }
                });
            }
        }).start();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除平台")
                .setMessage("确定删除「" + editing.name + "」？该平台的接口配置会一并清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.remove(editing.id);
                        Toast.makeText(PlatformEditActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .show();
    }

    // ================= 百度搭子 Cookie 导入 =================

    /**
     * 保存网页版 Cookie：本机校验后写入平台配置，并自动启用桌面端签到。
     *
     * 兼容三种粘贴方式：
     *   1. 纯 Cookie 串（一行）；
     *   2. 抓包复制的多行文本（Cookie: ... 加 csrfToken: ...）；
     *   3. 被换行拆断的 Cookie。
     * bce-user-info 不再强制：前端自己也会在缺失时发空 csrfToken，
     * 缺字段时只提示，不阻断，交给服务端判定。
     */
    private void saveDumateCookie() {
        String raw = etDumateCookie == null ? "" : etDumateCookie.getText().toString().trim();
        if (raw.length() == 0) {
            Toast.makeText(this, "请先粘贴百度搭子网页版 Cookie", Toast.LENGTH_SHORT).show();
            return;
        }

        Platform tmp = new Platform();
        tmp.credValue = raw;
        String cookie = Dumate.cookieOf(tmp);
        if (cookie.length() == 0) {
            Toast.makeText(this, "没有识别到 Cookie，请确认粘贴的是请求头里的 Cookie 值", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cookie.contains("BDUSS")) {
            Toast.makeText(this, "Cookie 缺少 BDUSS，请复制完整请求头", Toast.LENGTH_SHORT).show();
            return;
        }

        String csrfHeader = Dumate.headerValue(raw, "csrfToken");
        if (csrfHeader.length() == 0) {
            csrfHeader = Dumate.headerValue(etHeaders == null ? "" : etHeaders.getText().toString(), "csrfToken");
        }

        Platform p = collect();
        p.kind = "dumate";
        p.credValue = cookie;
        p.method = "POST";
        p.body = "{}";
        p.url = "https://dumate.baidu.com/api/dumate/points/loginBonus";
        p.preUrl = "https://dumate.baidu.com/api/dumate/points/loginBonusInfo";
        p.headers = dumateHeaders(csrfHeader);
        p.enabled = true;
        p.credIssuedAt = System.currentTimeMillis();
        p.lastWarnAt = 0L;
        p.renewUrl = "";
        p.renewMethod = "POST";
        p.renewHeaders = "";
        p.renewBody = "";
        p.renewExtract = "";
        p.renewEveryHours = 0;

        store.upsert(p);
        editing = p;
        etCred.setText(cookie);
        swEnabled.setChecked(true);
        updateCredState();
        updateDumateState();

        String csrf = Dumate.csrfOf(p, cookie);
        if (csrf.length() == 0) {
            Toast.makeText(this, "Cookie 已保存并启用；未找到 bce-user-info，将用空 csrfToken 尝试签到，若失败可把 csrfToken 请求头一起粘贴", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Cookie 已保存，桌面端签到已启用", Toast.LENGTH_SHORT).show();
        }
    }

    private String dumateHeaders(String csrf) {
        String csrfLine = (csrf == null || csrf.trim().length() == 0)
                ? "csrfToken: {{cookie:bce-user-info}}"
                : "csrfToken: " + csrf.trim();
        return "X-Dumate-Client-Type: desktop\n"
                + "Cookie: {{token}}\n"
                + csrfLine + "\n"
                + "Content-Type: application/json\n"
                + "Origin: https://dumate.baidu.com\n"
                + "Referer: https://dumate.baidu.com/app";
    }

    /** 百度搭子 Cookie 状态文案。 */
    private void updateDumateState() {
        if (tvDumateState == null || editing == null) {
            return;
        }
        if (!editing.isDumate() || !editing.hasDumateCookie()) {
            tvDumateState.setTextColor(getResources().getColor(R.color.text_hint));
            tvDumateState.setText("尚未配置 Cookie。粘贴后会自动识别 csrfToken，并按桌面端额度签到。");
            return;
        }
        String cookie = Dumate.cookieOf(editing);
        String csrf = Dumate.csrfOf(editing, cookie);
        if (csrf.length() == 0) {
            tvDumateState.setTextColor(getResources().getColor(R.color.fail));
            tvDumateState.setText("已配置 Cookie（长度 " + cookie.length()
                    + "），但没有 bce-user-info：将用空 csrfToken 尝试签到。若返回 403，请把同一请求的 csrfToken 请求头一起粘贴。");
            return;
        }
        tvDumateState.setTextColor(getResources().getColor(R.color.ok));
        tvDumateState.setText("已配置 Cookie（长度 " + cookie.length()
                + "，csrfToken 已识别）· 签到按桌面端额度发放");
    }

    // ================= TeleAgent 令牌导入（积分福利社） =================

    /**
     * 保存福利社网页的 operationToken：本机归一化后写入平台配置，并自动启用签到。
     * 设备号留空时自动生成一个稳定 UUID，之后固定使用。
     */
    private void saveTeleagentToken() {
        String raw = etTeleagentToken == null ? "" : etTeleagentToken.getText().toString();
        String token = com.marvis.signin.net.TeleAgent.normalize(raw);
        if (token.length() == 0) {
            Toast.makeText(this, "请先粘贴 operationToken 的值（或包含它的整行 Cookie）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token.length() < 16) {
            Toast.makeText(this, "令牌长度不对，请确认复制的是完整的 operationToken 值", Toast.LENGTH_SHORT).show();
            return;
        }

        String dev = etTeleagentDevice == null ? ""
                : etTeleagentDevice.getText().toString().trim();
        if (dev.length() == 0) {
            dev = editing.credDeviceId == null || editing.credDeviceId.trim().length() == 0
                    ? com.marvis.signin.net.TeleAgent.stableDeviceId()
                    : editing.credDeviceId.trim();
        }

        Platform p = collect();
        p.kind = "teleagent";
        p.credValue = token;
        p.credDeviceId = dev;
        p.method = "GET";
        p.body = "";
        p.url = "https://agent.teleai.com.cn/superCowork/sapi/user/portal/companyStore/v3/checkDayTask";
        p.enabled = true;
        p.credIssuedAt = System.currentTimeMillis();
        p.lastWarnAt = 0L;
        p.renewUrl = "";
        p.renewMethod = "GET";
        p.renewHeaders = "";
        p.renewBody = "";
        p.renewExtract = "";
        p.renewEveryHours = 0;

        store.upsert(p);
        editing = p;
        etCred.setText(token);
        etTeleagentDevice.setText(dev);
        etTeleagentToken.setText(""); // 明文令牌不留在输入框里
        swEnabled.setChecked(true);
        updateCredState();
        updateTeleagentState();
        Toast.makeText(this, "令牌已保存，TeleAgent 签到 + 福利社活动领取已启用", Toast.LENGTH_SHORT).show();
    }

    /** TeleAgent 令牌状态文案。 */
    private void updateTeleagentState() {
        if (tvTeleagentState == null || editing == null) {
            return;
        }
        if (!editing.hasTeleagentToken()) {
            tvTeleagentState.setTextColor(getResources().getColor(R.color.text_hint));
            tvTeleagentState.setText("尚未导入令牌。按上面的步骤拿到 operationToken 粘贴进来（注意不是 operToken），"
                    + "App 只在本机保存，不会上传。");
            return;
        }
        StringBuilder sb = new StringBuilder("已导入令牌（长度 "
                + (editing.credValue == null ? 0 : editing.credValue.trim().length()) + "）");
        long exp = com.marvis.signin.util.Cred.expiryMs(editing);
        if (exp > 0) {
            sb.append(" · ").append(exp <= System.currentTimeMillis()
                    ? "令牌可能已失效" : "有效至 " + fmtTime(exp));
        }
        sb.append((editing.credDeviceId != null && editing.credDeviceId.trim().length() > 0)
                ? " · 设备号已绑定" : " · 设备号将自动生成");
        tvTeleagentState.setTextColor(getResources().getColor(exp > 0
                && exp <= System.currentTimeMillis() ? R.color.fail : R.color.ok));
        tvTeleagentState.setText(sb.toString());
    }

    // ================= Trae 登录态导入（本机解密 storage.json） =================

    /**
     * 解析用户粘贴的 storage.json：AES-128-CBC 解密出登录态，提取 token 与 x-device-id。
     * 解密全部在本机完成，原文与明文都不会上传。
     */
    private void importTrae() {
        final String raw = etTraeStorage == null ? "" : etTraeStorage.getText().toString();
        if (raw.trim().length() == 0) {
            Toast.makeText(this, "请先粘贴 Trae 的 storage.json 内容", Toast.LENGTH_SHORT).show();
            return;
        }
        btnTraeImport.setEnabled(false);
        tvTraeState.setTextColor(getResources().getColor(R.color.text_sub));
        tvTraeState.setText("正在本机解密…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                Trae.Login login = null;
                String err = null;
                try {
                    login = Trae.parseStorage(raw);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "未知错误" : e.getMessage();
                }
                final Trae.Login parsed = login;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        btnTraeImport.setEnabled(true);
                        if (parsed == null) {
                            tvTraeState.setTextColor(getResources().getColor(R.color.fail));
                            tvTraeState.setText("导入失败：" + error);
                            Toast.makeText(PlatformEditActivity.this,
                                    "导入失败：" + error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Platform p = collect();
                        p.kind = "trae";
                        p.credValue = parsed.token;
                        p.credDeviceId = parsed.deviceId == null ? "" : parsed.deviceId;
                        p.credExpireAt = parsed.expiredAt;
                        p.credIssuedAt = System.currentTimeMillis();
                        p.lastWarnAt = 0L;
                        p.enabled = true; // 导入成功后自动启用，开始参与每日定时签到
                        if (p.url == null || p.url.trim().length() == 0) {
                            p.url = "https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim";
                        }
                        store.upsert(p);
                        editing = p;
                        etCred.setText(parsed.token);
                        etTraeDevice.setText(parsed.deviceId == null ? "" : parsed.deviceId);
                        etTraeStorage.setText(""); // 明文登录态不留在输入框里
                        swEnabled.setChecked(true);
                        updateCredState();
                        updateTraeState();
                        Toast.makeText(PlatformEditActivity.this,
                                "登录态已导入并保存", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** Trae 登录态状态文案。 */
    private void updateTraeState() {
        if (tvTraeState == null || editing == null) {
            return;
        }
        if (!editing.hasTraeToken()) {
            tvTraeState.setTextColor(getResources().getColor(R.color.text_hint));
            tvTraeState.setText("尚未导入。在电脑上打开 Trae 客户端，把 User/globalStorage/storage.json 全文粘贴到上面，"
                    + "App 会在本机解密出 token 与设备号。");
            return;
        }
        StringBuilder sb = new StringBuilder("已导入登录态");
        if (editing.credExpireAt > 0) {
            sb.append(" · 令牌有效至 ").append(fmtTime(editing.credExpireAt));
        }
        sb.append((editing.credDeviceId != null && editing.credDeviceId.trim().length() > 0)
                ? " · 设备号已绑定" : " · 未绑定设备号（签到可能被拒 9004）");
        tvTraeState.setTextColor(getResources().getColor(R.color.ok));
        tvTraeState.setText(sb.toString());
    }

    // ================= MiniMax 授权（RFC 8628 设备码流程） =================

    /** 发起授权：申请设备码 -> 打开浏览器让用户确认 -> 轮询换令牌。 */
    private void startMinimaxAuth() {
        if (sPolling) {
            Toast.makeText(this, "正在等待授权确认…", Toast.LENGTH_SHORT).show();
            return;
        }
        btnMinimaxAuth.setEnabled(false);
        tvMinimaxState.setTextColor(getResources().getColor(R.color.text_sub));
        tvMinimaxState.setText("正在申请设备码…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                Minimax.DeviceCode dc = null;
                String err = null;
                try {
                    dc = Minimax.requestDeviceCode();
                } catch (Exception e) {
                    err = e.getMessage() == null ? "未知错误" : e.getMessage();
                }
                final Minimax.DeviceCode code = dc;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (code == null) {
                            authFailed("申请设备码失败：" + error);
                            return;
                        }
                        // 记下 pending：中途进程被回收，回到应用还能接着轮询
                        authPrefs.edit()
                                .putString("mm_device_code", code.deviceCode)
                                .putString("mm_verifier", code.verifier)
                                .putInt("mm_interval", code.interval)
                                .putLong("mm_until",
                                        System.currentTimeMillis() + Math.max(code.expiresIn, 60) * 1000L)
                                .apply();
                        showAuthDialog(code);
                        startPolling(code);
                    }
                });
            }
        }).start();
    }

    /** 提示授权码与链接，交给用户在浏览器里确认。 */
    private void showAuthDialog(final Minimax.DeviceCode code) {
        final String url = code.verificationUriComplete.length() > 0
                ? code.verificationUriComplete : code.verificationUri;
        new AlertDialog.Builder(this)
                .setTitle("在浏览器里确认授权")
                .setMessage("授权码：" + code.userCode
                        + "\n\n点击「打开浏览器」跳转到 MiniMax 官方登录页，登录后点「确认授权」，再回到本应用。"
                        + "\n\n若浏览器打不开，用「复制链接」把地址粘到浏览器里。")
                .setPositiveButton("打开浏览器", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBrowser(url);
                    }
                })
                .setNeutralButton("复制链接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyText(url);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有找到浏览器，请复制链接到浏览器打开", Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MiniMax 授权链接", text));
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** 轮询换令牌，直到用户确认 / 超时 / 出错。 */
    private void startPolling(final Minimax.DeviceCode code) {
        if (sPolling) {
            // 已经有一条轮询线程在跑（页面重建也会走到这里），不要再起第二条
            if (tvMinimaxState != null) {
                tvMinimaxState.setTextColor(getResources().getColor(R.color.run));
                tvMinimaxState.setText("等待在浏览器中确认授权…（授权码 " + code.userCode + "）");
            }
            return;
        }
        sPolling = true;
        startGuardService();
        if (tvMinimaxState != null) {
            tvMinimaxState.setTextColor(getResources().getColor(R.color.run));
            tvMinimaxState.setText("等待在浏览器中确认授权…（授权码 " + code.userCode + "）");
        }

        sPollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = authPrefs.getLong("mm_until",
                        System.currentTimeMillis() + 300000L);
                int interval = Math.max(code.interval, 3);
                while (System.currentTimeMillis() < deadline && sPolling) {
                    try {
                        Minimax.Token t = Minimax.pollToken(code);
                        if (t != null) {
                            authSuccess(t);
                            return;
                        }
                    } catch (Minimax.AuthException e) {
                        authFailed(e.getMessage() == null ? "授权失败" : e.getMessage());
                        return;
                    } catch (Exception e) {
                        // 网络抖动：继续等，别打断用户
                    }
                    try {
                        Thread.sleep(interval * 1000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                if (sPolling) {
                    authFailed("等待授权超时，请重新发起授权");
                }
            }
        });
        sPollThread.start();
    }

    /** 授权成功：把 access + refresh 一起存下来。 */
    private void authSuccess(final Minimax.Token t) {
        final PlatformEditActivity act = sCurrent;
        if (act == null) {
            // 页面已经关了：令牌照样要落盘，否则这次授权就白费了
            sPolling = false;
            persistToken(t);
            stopGuardService();
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                sPolling = false;
                clearPendingAuth();
                btnMinimaxAuth.setEnabled(true);
                Platform p = collect();
                p = persistToken(t);
                p.enabled = true; // 授权成功后自动启用，开始参与每日定时签到
                store.upsert(p);
                editing = p;
                etCred.setText(t.accessToken);
                updateMinimaxState();
                Toast.makeText(PlatformEditActivity.this, "授权成功，令牌已保存", Toast.LENGTH_SHORT).show();
                stopGuardService();
            }
        });
    }

    private void authFailed(final String reason) {
        final PlatformEditActivity act = sCurrent;
        sPolling = false;
        if (act == null) {
            stopGuardService();
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                clearPendingAuth();
                btnMinimaxAuth.setEnabled(true);
                tvMinimaxState.setTextColor(getResources().getColor(R.color.fail));
                tvMinimaxState.setText("授权未完成：" + reason);
                Toast.makeText(PlatformEditActivity.this, "授权未完成：" + reason, Toast.LENGTH_LONG).show();
                stopGuardService();
            }
        });
    }

    /** 回到应用时，如果上一次授权还没结论，接着轮询。 */
    private void resumePendingAuth() {
        if (sPolling) {
            return;
        }
        String dc = authPrefs.getString("mm_device_code", "");
        String verifier = authPrefs.getString("mm_verifier", "");
        long until = authPrefs.getLong("mm_until", 0L);
        if (dc == null || dc.length() == 0 || verifier == null || verifier.length() == 0) {
            return;
        }
        if (System.currentTimeMillis() > until) {
            clearPendingAuth();
            return;
        }
        Minimax.DeviceCode code = new Minimax.DeviceCode();
        code.deviceCode = dc;
        code.verifier = verifier;
        code.interval = authPrefs.getInt("mm_interval", 3);
        startPolling(code);
    }

    private void clearPendingAuth() {
        authPrefs.edit()
                .remove("mm_device_code")
                .remove("mm_verifier")
                .remove("mm_interval")
                .remove("mm_until")
                .apply();
    }

    /** 授权等待期间用前台服务保活，避免切到浏览器后进程被回收导致轮询中断。 */
    /**
     * 授权期间借守护前台服务保活（切到浏览器后进程容易被回收）。
     * 注意：守护服务也是用户的「签到守护」开关在用，所以只有「用户本来没开守护」时
     * 才由我们拉起，也只有我们自己拉起的才由我们停——不能把用户的守护关掉。
     */
    private void startGuardService() {
        try {
            if (store.isGuardOn()) {
                sGuardStartedByUs = false;
                return;
            }
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            sGuardStartedByUs = true;
        } catch (Exception ignored) {
            sGuardStartedByUs = false;
        }
    }

    private void stopGuardService() {
        if (!sGuardStartedByUs) {
            return; // 不是我们拉起的，别动用户的守护
        }
        sGuardStartedByUs = false;
        try {
            stopService(new Intent(this, KeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    /** 把新的 access / refresh 令牌写进平台配置并落盘（refresh token 必须一起存）。 */
    private Platform persistToken(Minimax.Token t) {
        Platform p = collect();
        p.kind = "minimax";
        p.credValue = t.accessToken;
        p.credRefresh = t.refreshToken;
        p.credExpireAt = System.currentTimeMillis() + Math.max(t.expiresIn, 60) * 1000L;
        p.credIssuedAt = System.currentTimeMillis();
        p.lastWarnAt = 0L;
        return p;
    }

    /** MiniMax 授权状态文案。 */
    private void updateMinimaxState() {
        if (tvMinimaxState == null || editing == null) {
            return;
        }
        if (!editing.hasMinimaxToken()) {
            tvMinimaxState.setTextColor(getResources().getColor(R.color.text_hint));
            tvMinimaxState.setText("尚未授权。授权一次之后，签到会自动刷新令牌，长期可用。");
            return;
        }
        String s = "已授权 · 访问令牌";
        if (editing.credExpireAt > 0) {
            s += "有效至 " + fmtTime(editing.credExpireAt);
        } else {
            s += "已保存";
        }
        s += (editing.credRefresh != null && editing.credRefresh.length() > 0)
                ? " · 刷新令牌已绑定（可自动续期）" : " · 没有刷新令牌（过期需重新授权）";
        tvMinimaxState.setTextColor(getResources().getColor(R.color.ok));
        tvMinimaxState.setText(s);
    }

    private String fmtTime(long ms) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
        } catch (Exception e) {
            return "";
        }
    }
}
