package group.gnometrading.websockets.utils;

import java.nio.CharBuffer;

public class CharBufferUtils {

    /**
     * Returns true if the input CharBuffer ends with the `check` string.
     * <p/>
     * The input buffer must be at least the length of `check` or false is returned.
     * @param buf the buffer to evaluate against
     * @param check the string which the buffer should end with
     */
    public static boolean endsWith(CharBuffer buf, String check) {
        if (buf.length() < check.length()) {
            return false;
        }

        for (int offset = 1; offset <= check.length(); offset++) {
            if (check.charAt(check.length() - offset) != buf.charAt(buf.length() - offset)) {
                return false;
            }
        }

        return true;
    }
}
