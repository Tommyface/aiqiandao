package com.marvis.signin.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 极简 HTTP 发包器：不引第三方库，只用 HttpURLConnection。 */
public class Http {

    public static class Result {
        public int code = -1;
        public String body = "";
        public String error = "";
        public long cost = 0;
        /** 抓包用：本次请求的完整报文。 */
        public String reqDump = "";
        /** 抓包用：本次响应的完整报文。 */
        public String respDump = "";
        /** 响应里的 Set-Cookie 原始值，供 Cookie 自动续期使用。 */
        public List<String> setCookies = new ArrayList<String>();

        public boolean httpOk() {
            return code >= 200 && code < 300;
        }
    }

    public static Result execute(String url, String method, String headersText, String body, int timeoutMs) {
        return execute(url, method, headersText, body, timeoutMs, null);
    }

    /**
     * @param cookieHeader Cookie 自动续期带入的 Cookie 串；用户请求头里已手写 Cookie 时以其为准。
     */
    public static Result execute(String url, String method, String headersText, String body,
                                 int timeoutMs, String cookieHeader) {
        return execute(url, method, headersText, body, timeoutMs, cookieHeader, null);
    }

    /**
     * 带自定义 Content-Type 的发包。
     *
     * @param contentType 为 null 时沿用默认（POST=application/json）；传 "application/x-www-form-urlencoded"
     *                    走表单编码（OAuth 设备码 / 刷新令牌接口要求这种格式）。
     */
    public static Result execute(String url, String method, String headersText, String body,
                                 int timeoutMs, String cookieHeader, String contentType) {
        Result r = new Result();
        long begin = System.currentTimeMillis();
        HttpURLConnection conn = null;
        StringBuilder reqLog = new StringBuilder();
        String realMethod = "POST".equalsIgnoreCase(method) ? "POST" : "GET";
        reqLog.append(realMethod).append(' ').append(url).append('\n');
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(realMethod);

            String ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
            conn.setRequestProperty("User-Agent", ua);
            reqLog.append("User-Agent: ").append(ua).append('\n');

            String accept = "application/json, text/plain, */*";
            conn.setRequestProperty("Accept", accept);
            reqLog.append("Accept: ").append(accept).append('\n');

            // 解析 "Key: Value" 形式的请求头
            boolean hasCookieHeader = false;
            boolean hasContentType = false;
            if (headersText != null) {
                String[] lines = headersText.split("\n");
                for (String line : lines) {
                    String s = line.trim();
                    if (s.length() == 0 || s.startsWith("#")) {
                        continue;
                    }
                    int idx = s.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String k = s.substring(0, idx).trim();
                    String v = s.substring(idx + 1).trim();
                    if (k.length() > 0) {
                        conn.setRequestProperty(k, v);
                        if ("Cookie".equalsIgnoreCase(k)) {
                            hasCookieHeader = true;
                        }
                        if ("Content-Type".equalsIgnoreCase(k)) {
                            hasContentType = true;
                        }
                    }
                }
            }

            // Cookie 自动续期：用户没手写 Cookie 时，把上次响应回收的 Cookie 自动带上
            if (!hasCookieHeader && cookieHeader != null && cookieHeader.length() > 0) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }

            // 记录实际生效的请求头（不含上面已记录的 UA / Accept）
            Map<String, List<String>> reqHeaders = conn.getRequestProperties();
            if (reqHeaders != null) {
                for (Map.Entry<String, List<String>> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null || "User-Agent".equalsIgnoreCase(k) || "Accept".equalsIgnoreCase(k)) {
                        continue;
                    }
                    List<String> vs = e.getValue();
                    if (vs == null || vs.isEmpty() || vs.get(0) == null) {
                        continue;
                    }
                    reqLog.append(k).append(": ").append(vs.get(0)).append('\n');
                }
            }
            reqLog.append('\n').append(body == null ? "" : body);
            r.reqDump = reqLog.toString();

            boolean hasBody = body != null && body.length() > 0;
            if ("POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                // 用户在请求头里自己写了 Content-Type 就用用户的，别悄悄改掉
                if (!hasContentType) {
                    conn.setRequestProperty("Content-Type", contentType != null && contentType.length() > 0
                            ? contentType : "application/json; charset=utf-8");
                }
                byte[] data = body == null ? new byte[0] : body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(data.length);
                // 必须真的取一次输出流：否则 HttpURLConnection 会把 POST 悄悄降级成 GET
                OutputStream os = conn.getOutputStream();
                if (data.length > 0) {
                    os.write(data);
                    os.flush();
                }
                os.close();
            } else if (!hasBody) {
                // 无 body 的 GET，直接进入响应读取
            }

            r.code = conn.getResponseCode();
            InputStream is;
            if (r.code >= 400) {
                is = conn.getErrorStream();
                if (is == null) {
                    // 有些网关（带 JSON 错误体的 401/403）不会给出错误流，
                    // 这时从正常流里读，否则错误信息会整段丢失
                    try {
                        is = conn.getInputStream();
                    } catch (Exception ignored) {
                        is = null;
                    }
                }
            } else {
                is = conn.getInputStream();
            }
            r.body = readAll(is, 8192);

            r.cost = System.currentTimeMillis() - begin;

            // 抓包：记录响应状态行、响应头、响应体，并回收 Set-Cookie
            StringBuilder respLog = new StringBuilder();
            respLog.append("HTTP ").append(r.code).append("  ·  ").append(r.cost).append("ms\n");
            Map<String, List<String>> hs = conn.getHeaderFields();
            if (hs != null) {
                for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                    String k = e.getKey();
                    List<String> vs = e.getValue();
                    if (vs == null) {
                        continue;
                    }
                    for (String v : vs) {
                        if (k == null) {
                            respLog.append(v).append('\n');
                        } else {
                            respLog.append(k).append(": ").append(v).append('\n');
                        }
                    }
                }
                List<String> cookies = hs.get("Set-Cookie");
                if (cookies != null) {
                    r.setCookies.addAll(cookies);
                }
            }
            respLog.append('\n').append(r.body);
            r.respDump = respLog.toString();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            r.cost = System.currentTimeMillis() - begin;
            if (r.reqDump.length() == 0) {
                r.reqDump = realMethod + " " + url + "\n\n" + (body == null ? "" : body);
            }
            r.respDump = "请求失败：" + r.error;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    /** 表单发包（application/x-www-form-urlencoded）：OAuth 设备码 / 刷新令牌接口使用。 */
    public static Result executeForm(String url, Map<String, String> form, int timeoutMs) {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
            }
        }
        return execute(url, "POST", "", sb.toString(), timeoutMs, null,
                "application/x-www-form-urlencoded; charset=utf-8");
    }

    /** URL 编码（UTF-8）。 */
    public static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream is, int limit) {
        if (is == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            int total = 0;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= limit) {
                    break;
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }
}
