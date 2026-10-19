package com.reporead;

/** Text PostgreSQL can store exactly: no NUL and no unpaired UTF-16 surrogate that the driver would replace. */
public final class StoredText {
    private StoredText() {}

    public static boolean storable(String text) {
        return text.codePoints().noneMatch(c -> c == 0 || Character.getType(c) == Character.SURROGATE);
    }
}
