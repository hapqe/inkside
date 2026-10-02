package me.hapke.inkside;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which physical pen buttons (from pen_remap Ctrl+4 / Ctrl+5) are bound to
 * the app's primary / secondary roles. Capture in Settings by tapping Map…
 * then pressing the pen button (works without hover).
 */
final class PenButtonStore {
    /** pen_remap: PAGEDOWN → Ctrl+4 */
    static final int HW_PEN_A = 1;
    /** pen_remap: PAGEUP → Ctrl+5 */
    static final int HW_PEN_B = 2;
    static final int HW_NONE = 0;

    /** @deprecated aliases kept for existing prefs */
    static final int HW_STYLUS_PRIMARY = HW_PEN_A;
    static final int HW_STYLUS_SECONDARY = HW_PEN_B;

    private static final String PREFS = "cc_pen_buttons";
    private static final String KEY_PRIMARY_HW = "primaryHw";
    private static final String KEY_SECONDARY_HW = "secondaryHw";

    private static PenButtonStore instance;
    private final SharedPreferences sp;
    private int primaryHw = HW_PEN_A;
    private int secondaryHw = HW_PEN_B;

    private PenButtonStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        primaryHw = normalizeHw(sp.getInt(KEY_PRIMARY_HW, HW_PEN_A));
        secondaryHw = normalizeHw(sp.getInt(KEY_SECONDARY_HW, HW_PEN_B));
        if (!sp.contains(KEY_PRIMARY_HW) && sp.contains("primary")) {
            primaryHw = HW_PEN_A;
            secondaryHw = HW_PEN_B;
            persist();
        }
    }

    static PenButtonStore get(Context ctx) {
        if (instance == null) instance = new PenButtonStore(ctx);
        return instance;
    }

    int getPrimaryHw() {
        return primaryHw;
    }

    int getSecondaryHw() {
        return secondaryHw;
    }

    /** Bind a physical button to a role; clears the other role if it used the same button. */
    void assign(boolean primaryRole, int hw) {
        hw = normalizeHw(hw);
        if (hw == HW_NONE) return;
        if (primaryRole) {
            primaryHw = hw;
            if (secondaryHw == hw) secondaryHw = HW_NONE;
        } else {
            secondaryHw = hw;
            if (primaryHw == hw) primaryHw = HW_NONE;
        }
        persist();
    }

    /**
     * @param hw {@link #HW_PEN_A} or {@link #HW_PEN_B}
     * @return 1 = primary role, 2 = secondary role, 0 = unmapped
     */
    int roleForHardware(int hw) {
        hw = normalizeHw(hw);
        if (hw == HW_NONE) return 0;
        if (primaryHw == hw) return 1;
        if (secondaryHw == hw) return 2;
        return 0;
    }

    static String statusLabel(int hw) {
        if (hw == HW_PEN_A || hw == HW_PEN_B) return "Registered";
        return "Not mapped";
    }

    private void persist() {
        sp.edit()
                .putInt(KEY_PRIMARY_HW, primaryHw)
                .putInt(KEY_SECONDARY_HW, secondaryHw)
                .apply();
    }

    private static int normalizeHw(int hw) {
        if (hw == HW_PEN_A || hw == HW_PEN_B) return hw;
        return HW_NONE;
    }
}
