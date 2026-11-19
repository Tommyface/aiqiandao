package android.util;

import java.nio.charset.StandardCharsets;

/** 最小 Android Base64 桩：只实现本项目用到的 URL_SAFE/NO_PADDING/NO_WRAP 组合。 */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static final int NO_PADDING = 1;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] input, int flags) {
        java.util.Base64.Encoder enc;
        if ((flags & URL_SAFE) != 0) {
            enc = java.util.Base64.getUrlEncoder();
        } else {
            enc = java.util.Base64.getEncoder();
        }
        if ((flags & NO_PADDING) != 0) {
            enc = enc.withoutPadding();
        }
        return enc.encodeToString(input);
    }

    public static byte[] decode(String s, int flags) {
        if ((flags & URL_SAFE) != 0) {
            return java.util.Base64.getUrlDecoder().decode(s);
        }
        return java.util.Base64.getDecoder().decode(s);
    }
}
