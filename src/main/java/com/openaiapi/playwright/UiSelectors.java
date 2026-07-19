package com.openaiapi.playwright;

/** Opaque robot UI selectors (must match open_ai_cursor_api/public/index.html). */
public final class UiSelectors {

    public static final String P1 = "p1";
    public static final String P2 = "p2";
    public static final String P3 = "p3";
    public static final String A0 = "a0";

    public static final String STATE_IDLE = "0";
    public static final String STATE_LOADING = "1";
    public static final String STATE_DONE = "2";
    public static final String STATE_ERROR = "3";

    private UiSelectors() {
    }

    public static String testId(String id) {
        return "[data-testid='" + id + "']";
    }
}
