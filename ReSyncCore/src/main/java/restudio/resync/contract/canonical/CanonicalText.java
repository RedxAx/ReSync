package restudio.resync.contract.canonical;

public final class CanonicalText {
    private CanonicalText() {
    }

    public static boolean isNfc(String value) {
        int length = value.length();
        for (int index = 0; index < length; ) {
            int code = value.codePointAt(index);
            int type = Character.getType(code);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                return false;
            }
            index += Character.charCount(code);
        }
        return true;
    }
}
