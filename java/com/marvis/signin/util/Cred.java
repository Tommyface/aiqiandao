package com.marvis.signin.util;

import android.util.Base64;

import com.marvis.signin.model.Platform;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 凭据有效期：到期时间来自两处，优先级从高到低
 *   1) 凭据本身是 JWT 时，直接解 payload 里的 exp（最准，无需用户填）
 *   2) 用户填的「有效期天数」+ 凭据填入时间（适用于非 JWT 的私有 token，如 Qoder 约 16 天）
 * 两者都拿不到时不做到期提醒，界面也不显示多余文案。
 */
public class Cred {

    /** 距离到期多少天开始提醒（同时作为提前续期的触发阈值）。 */
    public static final long WARN_AHEAD_MS = 2L * 24 * 3600 * 1000;
    /** 同一条凭据的提醒冷却时间，避免每次签到都弹。 */
    private static final long WARN_COOLDOWN_MS = 6L * 3600 * 1000;

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    /** 尝试解出 JWT 的 exp（毫秒）；非 JWT、无 exp 或格式异常一律返回 0。 */
    public static long jwtExpMs(String token) {
        if (token == null) {
            return 0;
        }
        String t = token.trim();
        if (t.startsWith("Bearer ") || t.startsWith("bearer ")) {
            t = t.substring(7).trim();
        }
        String[] parts = t.split("\\.");
        if (parts.length < 2) {
            return 0;
        }
        try {
            String json = new String(Base64.decode(pad(parts[1]),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "UTF-8");
            String v = Vars.jsonPick(json, "$.exp");
            if (v == null || v.length() == 0) {
                return 0;
            }
            double sec = Double.parseDouble(v.replace("\"", "").trim());
            return sec <= 0 ? 0 : (long) (sec * 1000L);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 凭据到期时间戳（毫秒），0 表示未知/不跟踪。 */
    public static long expiryMs(Platform p) {
        if (p == null) {
            return 0;
        }
        // MiniMax：access token 只有 1 小时左右，到期由 refresh token 自动续，不走提醒
        if (p.isMinimax()) {
            return p.credExpireAt;
        }
        // Trae：storage.json 里的 expiredAt 最准，优先用它
        if (p.isTrae() && p.credExpireAt > 0) {
            return p.credExpireAt;
        }
        // 百度搭子：Cookie 不是 JWT，只有明确填了到期时间才跟踪
        if (p.isDumate()) {
            if (p.credExpireAt > 0) {
                return p.credExpireAt;
            }
            if (p.credIssuedAt > 0 && p.credValidHours > 0) {
                return p.credIssuedAt + p.credValidHours * 3600L * 1000L;
            }
            return 0;
        }
        long jwt = jwtExpMs(p.credValue);
        if (jwt > 0) {
            return jwt;
        }
        if (p.credIssuedAt > 0 && p.credValidHours > 0) {
            return p.credIssuedAt + p.credValidHours * 3600L * 1000L;
        }
        return 0;
    }

    /** 到期时间的人类可读形式，未知返回空串。 */
    public static String expiryText(Platform p) {
        long exp = expiryMs(p);
        return exp <= 0 ? "" : FMT.format(new Date(exp));
    }

    /** 列表里的一句话状态，例如「凭据 14 天后过期」「凭据已过期 3 小时」；不跟踪返回空串。 */
    public static String label(Platform p) {
        if (p != null && p.isMinimax()) {
            if (!p.hasMinimaxToken()) {
                return "未授权 · 点「授权 MiniMax」登录一次";
            }
            if (p.credExpireAt <= 0) {
                return "已授权 · 刷新令牌可自动续期";
            }
            long left = p.credExpireAt - System.currentTimeMillis();
            if (left <= 0) {
                return "访问令牌已过期 · 签到时自动刷新";
            }
            return "访问令牌 " + human(left) + "后过期 · 自动续期";
        }
        if (p != null && p.isDumate()) {
            if (!p.hasDumateCookie()) {
                return "未配置 Cookie · 点进配置页粘贴网页版 Cookie";
            }
            long dmExp = expiryMs(p);
            if (dmExp <= 0) {
                return "已配置 Cookie · 桌面端签到";
            }
            long dmLeft = dmExp - System.currentTimeMillis();
            return dmLeft <= 0 ? "Cookie 可能已失效 · 请重新登录更新"
                    : "Cookie " + human(dmLeft) + "后到期";
        }
        if (p != null && p.isTrae()) {
            if (!p.hasTraeToken()) {
                return "未导入登录态 · 点进配置页粘贴 storage.json";
            }
            if (p.credExpireAt <= 0) {
                return "已导入登录态";
            }
            long left = p.credExpireAt - System.currentTimeMillis();
            if (left <= 0) {
                return "登录态已过期 · 请重新导入 storage.json";
            }
            return "令牌 " + human(left) + "后过期";
        }
        if (p != null && p.isTeleagent()) {
            if (!p.hasTeleagentToken()) {
                return "未导入令牌 · 点进配置页粘贴 operationToken";
            }
            long exp = expiryMs(p);
            if (exp <= 0) {
                return "已导入令牌 · 每日签到 + 福利社活动";
            }
            long left = exp - System.currentTimeMillis();
            if (left <= 0) {
                return "令牌可能已失效 · 请重新获取 operationToken";
            }
            return "令牌 " + human(left) + "后过期";
        }
        long exp = expiryMs(p);
        if (exp <= 0) {
            return "";
        }
        long left = exp - System.currentTimeMillis();
        if (left <= 0) {
            return "凭据已过期 " + human(-left);
        }
        return "凭据 " + human(left) + "后过期";
    }

    /** 是否已过期。 */
    public static boolean expired(Platform p) {
        long exp = expiryMs(p);
        return exp > 0 && System.currentTimeMillis() >= exp;
    }

    /** 是否到了该提前续期/提醒的窗口（到期前 2 天内，含已过期）。 */
    public static boolean inWarnWindow(Platform p, long now) {
        // MiniMax 的访问令牌由 refresh token 自动轮换，不需要提醒
        if (p != null && p.isMinimax()) {
            return false;
        }
        long exp = expiryMs(p);
        return exp > 0 && now >= exp - WARN_AHEAD_MS;
    }

    /** 这一次签到要不要弹提醒（同一凭据有冷却，避免刷屏）。 */
    public static boolean warnNeeded(Platform p, long now) {
        if (!inWarnWindow(p, now)) {
            return false;
        }
        return p.lastWarnAt <= 0 || now - p.lastWarnAt >= WARN_COOLDOWN_MS;
    }

    /** 提醒文案。 */
    public static String warnText(Platform p) {
        long exp = expiryMs(p);
        long left = exp - System.currentTimeMillis();
        if (p != null && p.isDumate()) {
            return left <= 0
                    ? "百度搭子 Cookie 可能已失效，请重新登录网页版更新"
                    : "百度搭子 Cookie 将在 " + human(left) + "后到期，记得提前更新";
        }
        if (left <= 0) {
            return "凭据已过期，签到会失败，请更新 token";
        }
        return "凭据将在 " + human(left) + "后过期，记得提前更新";
    }

    private static String human(long ms) {
        if (ms < 3600L * 1000L) {
            return "不到 1 小时";
        }
        if (ms < 24L * 3600L * 1000L) {
            return (ms / (3600L * 1000L)) + " 小时";
        }
        long days = (ms + 24L * 3600L * 1000L - 1) / (24L * 3600L * 1000L);
        return days + " 天";
    }

    private static String pad(String s) {
        String r = s;
        while (r.length() % 4 != 0) {
            r = r + "=";
        }
        return r;
    }
}
