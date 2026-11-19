package com.marvis.signin.net;

import android.util.Base64;

import com.marvis.signin.util.Http;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Trae CN（字节 Trae IDE）协议层。
 *
 * 登录态来源：Trae 客户端把凭据加密存在本机 storage.json 的
 *   iCubeAuthInfo://icube.cloudide
 * 字段里。格式为 base64([6字节头][32字节随机盐][AES-128-CBC 密文])，
 * 密钥派生 = SHA512( SHA512(random) || (SALT_A XOR SALT_B) )，前 16 字节作 key、后 16 字节作 iv；
 * 明文 = [64字节 SHA-512 校验][JSON 正文]。App 只在本机解密，不上传任何数据。
 *
 * 设备号：storage.json 里以 iCubeAuthInfo://icube-dc: 开头的键的后缀，即 x-device-id。
 *   claim 接口不带这个头会被上游拒绝（code 9004）。
 *
 * 签到：与网页端一致的两步接口
 *   POST /status -> { code, enable, checked_in, credits }
 *   POST /claim  -> { code, credits }（上游自带幂等；9074 = 仅限官方客户端）
 */
public class Trae {

    public static final String API_BASE = "https://api.trae.cn/trae/api/v2/ug/checkin_credits";
    public static final String AUTH_KEY = "iCubeAuthInfo://icube.cloudide";
    public static final String DC_PREFIX = "iCubeAuthInfo://icube-dc:";

    private static final int[] SALT_A = {
            82, 9, 106, 213, 48, 54, 165, 56, 191, 64, 163, 158, 129, 243, 215, 251,
            124, 227, 57, 130, 155, 47, 255, 135, 52, 142, 67, 68, 196, 222, 233, 203,
            84, 123, 148, 50, 166, 194, 35, 61, 238, 76, 149, 11, 66, 250, 195, 78,
            8, 46, 161, 102, 40, 217, 36, 178, 118, 91, 162, 73, 109, 139, 209, 37,
    };
    private static final int[] SALT_B = {
            31, 221, 168, 51, 136, 7, 199, 49, 177, 18, 16, 89, 39, 128, 236, 95,
            96, 81, 127, 169, 25, 181, 74, 13, 45, 229, 122, 159, 147, 201, 156, 239,
            160, 224, 59, 77, 174, 42, 245, 176, 200, 235, 187, 60, 131, 83, 153, 97,
            23, 43, 4, 126, 186, 119, 214, 38, 225, 105, 20, 99, 85, 33, 12, 125,
    };
    private static final byte[] HEADER_AES = {116, 99, 5, 16, 0, 0};
    private static final byte[] HEADER_AES_PRIVATE = {18, 57, 32, 32, 2, 3};

    /** 解析出来的登录态。 */
    public static class Login {
        public String token = "";
        public String refreshToken = "";
        public String userId = "";
        public String deviceId = "";
        /** 令牌到期时间戳（毫秒），0 表示未知。 */
        public long expiredAt = 0L;
        public String expiredAtText = "";
    }

    /** 解析错误直接给用户看。 */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    // ---------------- 登录态解析 ----------------

    /**
     * 解析用户粘贴的内容。支持三种：
     *   1) storage.json 全文（推荐）
     *   2) iCubeAuthInfo://icube.cloudide 的密文值
     *   3) 已解密的登录态 JSON 或裸 token
     */
    public static Login parseStorage(String raw) throws ParseException {
        if (raw == null || raw.trim().length() == 0) {
            throw new ParseException("请先粘贴 storage.json 内容");
        }
        String s = raw.trim();
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1).trim();
        }

        Login login = new Login();
        String encoded = s;
        JSONObject auth = null;

        if (s.startsWith("{")) {
            JSONObject storage;
            try {
                storage = new JSONObject(s);
            } catch (Exception e) {
                throw new ParseException("不是合法的 JSON：" + e.getMessage());
            }
            if (storage.has("token") || storage.has("accessToken")) {
                // 用户直接粘贴了解密后的登录态 JSON
                auth = storage;
            } else {
                encoded = storage.optString(AUTH_KEY, "");
                if (encoded.trim().length() == 0) {
                    throw new ParseException("storage.json 里没有 " + AUTH_KEY + " 字段");
                }
                login.deviceId = deviceFromStorage(storage);
            }
        }

        if (auth == null) {
            String plain = decrypt(encoded);
            try {
                auth = new JSONObject(plain);
            } catch (Exception e) {
                throw new ParseException("解密后的内容不是 JSON：" + e.getMessage());
            }
        }

        login.token = auth.optString("token", auth.optString("accessToken", "")).trim();
        if (login.token.length() == 0) {
            throw new ParseException("解密成功但没有 token 字段");
        }
        login.refreshToken = auth.optString("refreshToken", "");
        login.userId = auth.optString("userId", "");
        String expired = auth.optString("expiredAt", "");
        if (login.token.length() > 0) {
            long jwt = com.marvis.signin.util.Cred.jwtExpMs(login.token);
            if (jwt > 0) {
                login.expiredAt = jwt;
            }
        }
        if (expired.length() > 0) {
            long t = parseTime(expired);
            if (t > 0 && (login.expiredAt <= 0 || t < login.expiredAt)) {
                login.expiredAt = t;
            }
            login.expiredAtText = expired;
        }
        return login;
    }

    /** 从 storage.json 里找设备号。 */
    private static String deviceFromStorage(JSONObject storage) {
        try {
            Iterator<String> it = storage.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null && k.startsWith(DC_PREFIX)) {
                    return k.substring(DC_PREFIX.length());
                }
            }
        } catch (Exception ignored) {
        }
        String dev = storage.optString("telemetry.devDeviceId", "");
        if (dev.length() > 0) {
            return dev;
        }
        String mid = storage.optString("telemetry.machineId", "");
        if (mid.length() > 0) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(mid.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                return sb.substring(0, 32);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    /** AES-128-CBC 解密 iCubeAuthInfo 的值。 */
    public static String decrypt(String encoded) throws ParseException {
        try {
            String b64 = encoded.replaceAll("\\s+", "");
            byte[] buffer = Base64.decode(pad(b64), Base64.DEFAULT);
            if (buffer.length <= 102) {
                throw new ParseException("密文太短，请确认粘贴的是完整字段值");
            }
            byte[] header = Arrays.copyOfRange(buffer, 0, 6);
            if (Arrays.equals(header, HEADER_AES_PRIVATE)) {
                throw new ParseException("这是 aes-private 加密格式，暂不支持；请升级 Trae 客户端后再试");
            }
            if (!Arrays.equals(header, HEADER_AES)) {
                throw new ParseException("不认识的加密头，可能不是 Trae 的 storage.json");
            }

            byte[] random = Arrays.copyOfRange(buffer, 6, 38);
            byte[] encrypted = Arrays.copyOfRange(buffer, 38, buffer.length);

            byte[] salt = new byte[64];
            for (int i = 0; i < 64; i++) {
                salt[i] = (byte) (SALT_A[i] ^ SALT_B[i]);
            }
            byte[] first = sha512(random);
            byte[] joined = new byte[first.length + salt.length];
            System.arraycopy(first, 0, joined, 0, first.length);
            System.arraycopy(salt, 0, joined, first.length, salt.length);
            byte[] derived = sha512(joined);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Arrays.copyOfRange(derived, 0, 16), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(derived, 16, 32)));
            byte[] decrypted = cipher.doFinal(encrypted);
            if (decrypted.length < 64) {
                throw new ParseException("明文太短，完整性校验失败");
            }
            byte[] expected = Arrays.copyOfRange(decrypted, 0, 64);
            byte[] plain = Arrays.copyOfRange(decrypted, 64, decrypted.length);
            if (!Arrays.equals(expected, sha512(plain))) {
                throw new ParseException("完整性校验失败（SHA-512 不匹配），请重新复制 storage.json");
            }
            return new String(plain, "UTF-8");
        } catch (ParseException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ParseException("base64 解码失败，请检查是否复制完整");
        } catch (Exception e) {
            throw new ParseException("解密失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    private static String pad(String s) {
        String r = s;
        while (r.length() % 4 != 0) {
            r = r + "=";
        }
        return r;
    }

    /** 宽松解析 ISO 时间（忽略毫秒与时区偏移，只用于到期提醒展示）。 */
    private static long parseTime(String s) {
        try {
            String t = s.trim().replace('T', ' ');
            if (t.length() > 19) {
                t = t.substring(0, 19);
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            return f.parse(t).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------------- 签到接口 ----------------

    /** 查今天的签到状态。 */
    public static Http.Result status(String token, String deviceId) {
        return Http.execute(API_BASE + "/status", "POST",
                authHeaders(token, deviceId), "{}", 20000, null);
    }

    /** 领取今日积分。 */
    public static Http.Result claim(String token, String deviceId) {
        return Http.execute(API_BASE + "/claim", "POST",
                authHeaders(token, deviceId), "{}", 20000, null);
    }

    private static String authHeaders(String token, String deviceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Authorization: Cloud-IDE-JWT ").append(token == null ? "" : token.trim()).append('\n');
        sb.append("Content-Type: application/json\n");
        sb.append("Accept: application/json");
        if (deviceId != null && deviceId.trim().length() > 0) {
            sb.append('\n').append("x-device-id: ").append(deviceId.trim());
        }
        return sb.toString();
    }

    // ---------------- 响应解析 ----------------

    public static JSONObject root(Http.Result r) {
        if (r == null || r.body == null || r.body.trim().length() == 0) {
            return null;
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    public static int codeOf(Http.Result r) {
        JSONObject o = root(r);
        return o == null ? -1 : o.optInt("code", 0);
    }

    /** 活动是否开启；字段缺失时按开启处理（宁可调用 claim，也不要把成功误判成无活动）。 */
    public static boolean isEnabled(Http.Result r) {
        JSONObject o = root(r);
        return o == null || !o.has("enable") || o.optBoolean("enable", true);
    }

    public static boolean isCheckedIn(Http.Result r) {
        JSONObject o = root(r);
        return o != null && o.optBoolean("checked_in", false);
    }

    public static int credits(Http.Result r) {
        JSONObject o = root(r);
        return o == null ? 0 : o.optInt("credits", 0);
    }

    public static String messageOf(Http.Result r) {
        JSONObject o = root(r);
        return o == null ? "" : o.optString("message", "");
    }

    /** 上游错误原因（优先 message，其次原始响应片段）。 */
    public static String upstreamError(Http.Result r) {
        JSONObject o = root(r);
        if (o == null) {
            return cut(r == null ? "" : r.body, 120);
        }
        String msg = o.optString("message", "");
        if (msg.length() > 0) {
            return "code " + o.optInt("code", 0) + "：" + msg;
        }
        return cut(r == null ? "" : r.body, 120);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
