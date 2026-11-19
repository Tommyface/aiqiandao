package com.marvis.signin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.marvis.signin.data.TraceStore;
import com.marvis.signin.model.Trace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 抓包助手：查看每次签到的完整请求与响应，配接口时不再需要外部抓包工具。 */
public class TraceActivity extends Activity {

    private LinearLayout container;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trace);
        container = (LinearLayout) findViewById(R.id.trace_container);
        tvEmpty = (TextView) findViewById(R.id.tv_trace_empty);
        findViewById(R.id.btn_trace_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_trace_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        List<Trace> list = TraceStore.list(this);
        if (list.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (final Trace t : list) {
            View item = View.inflate(this, R.layout.item_trace, null);
            TextView tvTitle = (TextView) item.findViewById(R.id.t_title);
            TextView tvMeta = (TextView) item.findViewById(R.id.t_meta);
            TextView tvBody = (TextView) item.findViewById(R.id.t_body);

            boolean ok = t.code >= 200 && t.code < 300;
            String status = t.code > 0 ? ("HTTP " + t.code) : "无响应";
            tvTitle.setText(t.platformName + " · " + status);
            tvTitle.setTextColor(getResources().getColor(ok ? R.color.ok : R.color.fail));
            tvMeta.setText(t.method + " " + shortUrl(t.url) + "  ·  " + t.cost + "ms"
                    + (t.cookieCount > 0 ? "  ·  Cookie " + t.cookieCount : ""));
            String preview = t.error != null && t.error.length() > 0 ? t.error : t.respDump;
            tvBody.setText(squash(preview));

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetail(t);
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    copy("【请求】\n" + t.reqDump + "\n\n【响应】\n" + t.respDump);
                    return true;
                }
            });
            container.addView(item);
        }
    }

    private void showDetail(final Trace t) {
        final String text = "【请求】\n" + t.reqDump + "\n\n【响应】\n" + t.respDump;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(getResources().getColor(R.color.text_main));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getResources().getColor(R.color.card_bg));
        sv.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(t.platformName + " · " + t.method)
                .setView(sv)
                .setPositiveButton("复制全文", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        copy(text);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空抓包记录")
                .setMessage("将删除全部本地抓包日志，不影响平台配置与签到记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        TraceStore.clear(TraceActivity.this);
                        refresh();
                    }
                })
                .show();
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("signin_trace", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        String u = url.replace("https://", "").replace("http://", "");
        return u.length() > 42 ? u.substring(0, 42) + "…" : u;
    }

    private static String squash(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
