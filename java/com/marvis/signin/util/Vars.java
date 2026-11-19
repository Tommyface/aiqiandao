package com.marvis.signin.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板变量替换：让同一条签到配置可以吃「动态值」。
 *
 * 支持四种占位符，可写在 URL / 请求头 / Body 任意位置：
 *   {{token}}              当前凭据值（签到失败或到期后由续期接口自动刷新）
 *   {{cookie:名字}}        从当前 Cookie 串里取该 cookie 的值，如 {{cookie:bce-user-info}}
 *   {{pre:$.a.b[0].c}}     从前置请求的响应 JSON 里取值（极简 JSONPath）
 *   {{pre.raw}}            前置请求的响应原文
 *
 * JSONPath 支持：$.a.b、数组下标 [0]、条件过滤 [k=v]（多个条件用 & 连接，取第一个匹配项），
 * 例如 $.campaigns[actionType=CLAIM_BENEFIT&claimStatus=CLAIMABLE].id
 */
public class Vars {

    private static final Pattern P = Pattern.compile(
            "\\{\\{\\s*(cookie|pre)\\s*:\\s*([^}]*?)\\s*\\}\\}"
                    + "|\\{\\{\\s*token\\s*\\}\\}"
                    + "|\\{\\{\\s*pre\\.raw\\s*\\}\\}");

    private static final Pattern PRE_ONLY = Pattern.compile(
            "\\{\\{\\s*pre\\s*:[^}]*\\}\\}|\\{\\{\\s*pre\\.raw\\s*\\}\\}");

    /** 一次性完成全部替换；preBody 为空时 {{pre:...}} 会替换为空串。 */
    public static String fill(String text, String cookieHeader, String preBody) {
        return fill(text, cookieHeader, preBody, "");
    }

    /** 带凭据的替换：{{token}} 会换成 credValue。 */
    public static String fill(String text, String cookieHeader, String preBody, String credValue) {
        if (text == null || text.length() == 0) {
            return text == null ? "" : text;
        }
        Matcher m = P.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String whole = m.group(0).replace(" ", "");
            String rep;
            if (whole.equals("{{pre.raw}}")) {
                rep = preBody == null ? "" : preBody;
            } else if (whole.equals("{{token}}")) {
                rep = credValue == null ? "" : credValue;
            } else if ("cookie".equals(m.group(1))) {
                rep = cookieValue(cookieHeader, m.group(2));
            } else {
                rep = jsonPick(preBody, m.group(2));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? "" : rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 是否含前置响应变量（用于判断「必须等前置请求」）。 */
    public static boolean needsPre(String text) {
        return text != null && PRE_ONLY.matcher(text).find();
    }

    /** 从 "a=1; b=2" 里取某个 cookie 的值，取不到返回空串。 */
    public static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || name == null) {
            return "";
        }
        String want = name.trim();
        for (String part : cookieHeader.split(";")) {
            String s = part.trim();
            int idx = s.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            if (s.substring(0, idx).trim().equals(want)) {
                return s.substring(idx + 1).trim();
            }
        }
        return "";
    }

    /** 极简 JSONPath 取值，失败返回空串，绝不抛异常。 */
    public static String jsonPick(String json, String path) {
        if (json == null || json.length() == 0) {
            return "";
        }
        try {
            Object cur = new JSONTokener(json).nextValue();
            String p = path == null ? "" : path.trim();
            if (p.startsWith("$")) {
                p = p.substring(1);
            }
            while (p.startsWith(".")) {
                p = p.substring(1);
            }
            if (p.length() == 0) {
                return stringify(cur);
            }
            for (String seg : splitSegments(p)) {
                if (seg.length() == 0) {
                    continue;
                }
                String name = seg;
                List<String> brackets = new ArrayList<String>();
                int b = seg.indexOf('[');
                if (b >= 0) {
                    name = seg.substring(0, b);
                    String rest = seg.substring(b);
                    int from = 0;
                    while (true) {
                        int l = rest.indexOf('[', from);
                        int r = rest.indexOf(']', from);
                        if (l < 0 || r < l) {
                            break;
                        }
                        brackets.add(rest.substring(l + 1, r).trim());
                        from = r + 1;
                    }
                }
                if (name.length() > 0) {
                    if (!(cur instanceof JSONObject)) {
                        return "";
                    }
                    cur = ((JSONObject) cur).opt(name);
                }
                for (String br : brackets) {
                    cur = step(cur, br);
                    if (cur == null) {
                        break;
                    }
                }
                if (cur == null) {
                    return "";
                }
            }
            return stringify(cur);
        } catch (Exception e) {
            return "";
        }
    }

    /** 按点号切分路径，但括号内的点不切（如条件里带小数点）。 */
    private static List<String> splitSegments(String p) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (c == '.' && depth == 0) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    /** 单层下标/过滤；失败返回 null。 */
    private static Object step(Object cur, String bracket) {
        if (bracket == null || bracket.length() == 0) {
            return cur;
        }
        boolean numeric = true;
        for (int i = 0; i < bracket.length(); i++) {
            if (!Character.isDigit(bracket.charAt(i))) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            if (!(cur instanceof JSONArray)) {
                return null;
            }
            return ((JSONArray) cur).opt(Integer.parseInt(bracket));
        }
        if (!(cur instanceof JSONArray)) {
            return null;
        }
        JSONArray arr = (JSONArray) cur;
        String[] conds = bracket.split("&");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            boolean ok = true;
            for (String cond : conds) {
                int eq = cond.indexOf('=');
                if (eq <= 0) {
                    ok = false;
                    break;
                }
                String k = cond.substring(0, eq).trim();
                String v = cond.substring(eq + 1).trim();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                String got = String.valueOf(o.opt(k) == null ? "" : o.opt(k));
                if (!got.equals(v)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return o;
            }
        }
        return null;
    }

    private static String stringify(Object o) {
        if (o == null || o == JSONObject.NULL) {
            return "";
        }
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof JSONObject || o instanceof JSONArray) {
            return o.toString();
        }
        return String.valueOf(o);
    }
}
