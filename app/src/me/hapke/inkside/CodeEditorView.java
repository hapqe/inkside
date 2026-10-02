package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.OverScroller;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Code editor with a line-number gutter, live syntax highlighting, auto-indent,
 * bracket auto-close and an undo stack.
 *
 * <p>Highlighting reuses {@link SyntaxHighlighter}, so the editor panel and any
 * other surfaces tokenize identically. Only the edited line range is re-spanned
 * per keystroke.
 */
final class CodeEditorView extends EditText {

    private static final int MAX_UNDO = 200;
    private static final String INDENT = "    ";
    /** Above this, per-keystroke full-buffer work is too slow; span only the local window. */
    private static final int LARGE_FILE_CHARS = 200_000;
    /** Slightly below platform default so flings coast farther (noticeable acceleration). */
    private static final float FLING_FRICTION_SCALE = 0.55f;

    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterBgPaint = new Paint();
    private final Paint currentLinePaint = new Paint();

    private ThemeConfig.CodeStyle style = ThemeConfig.CODE_STYLES[0];
    private int gutterWidthPx;
    private int gutterDigits = -1;

    private boolean suppressWatcher;
    private boolean highlightPending;

    private final OverScroller scroller;
    private VelocityTracker velocityTracker;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private float lastTouchY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean fingerDragging;

    private static final class Edit {
        final int start;
        final String before;
        final String after;
        final int cursorBefore;

        Edit(int start, String before, String after, int cursorBefore) {
            this.start = start;
            this.before = before;
            this.after = after;
            this.cursorBefore = cursorBefore;
        }
    }

    private final ArrayDeque<Edit> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Edit> redoStack = new ArrayDeque<>();
    private String pendingBefore;
    private int pendingStart;
    private int pendingCursor;
    private boolean viewOnly;
    private final android.text.method.KeyListener editKeyListener;
    private final int editInputType;

    CodeEditorView(Context context) {
        super(context);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        setHorizontallyScrolling(true);
        setInputType(EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        setBackground(null);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setVerticalScrollBarEnabled(true);
        editKeyListener = getKeyListener();
        editInputType = getInputType();

        scroller = new OverScroller(context);
        scroller.setFriction(ViewConfiguration.getScrollFriction() * FLING_FRICTION_SCALE);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();

        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setTextAlign(Paint.Align.RIGHT);

        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (suppressWatcher) return;
                pendingStart = start;
                pendingBefore = s.subSequence(start, start + count).toString();
                pendingCursor = getSelectionStart();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressWatcher) return;
                String after = s.subSequence(start, start + count).toString();
                pushUndo(new Edit(start, pendingBefore == null ? "" : pendingBefore,
                        after, pendingCursor));
                pendingBefore = null;
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;
                scheduleHighlight();
                invalidateGutter();
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                activePointerId = event.getPointerId(0);
                lastTouchY = event.getY();
                fingerDragging = false;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                activePointerId = event.getPointerId(index);
                lastTouchY = event.getY(index);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(activePointerId);
                if (index < 0) break;
                float y = event.getY(index);
                float dy = lastTouchY - y;
                if (!fingerDragging && Math.abs(dy) > touchSlop) {
                    fingerDragging = true;
                    dy = dy > 0 ? dy - touchSlop : dy + touchSlop;
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                if (fingerDragging) {
                    lastTouchY = y;
                    int oldY = getScrollY();
                    int target = clampScrollY(oldY + Math.round(dy));
                    if (target != oldY) {
                        scrollTo(getScrollX(), target);
                    }
                    // Consume so EditText does not also scroll / place the caret mid-drag.
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                if (event.getPointerId(index) == activePointerId) {
                    int newIndex = index == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(newIndex);
                    lastTouchY = event.getY(newIndex);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = fingerDragging;
                if (wasDragging && action == MotionEvent.ACTION_UP && velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float vy = velocityTracker.getYVelocity(activePointerId);
                    if (Math.abs(vy) > minFlingVelocity) {
                        // Negate: finger up → negative velocityY → content should move down.
                        fling(-Math.round(vy));
                    }
                }
                fingerDragging = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                if (wasDragging) return true;
                break;
            }
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void fling(int velocityY) {
        int maxY = maxScrollY();
        if (maxY <= 0) return;
        scroller.fling(
                getScrollX(), getScrollY(),
                0, velocityY,
                0, 0,
                0, maxY);
        postInvalidateOnAnimation();
    }

    private int maxScrollY() {
        Layout layout = getLayout();
        if (layout == null) return 0;
        int content = layout.getHeight() + getTotalPaddingTop() + getTotalPaddingBottom();
        return Math.max(0, content - getHeight());
    }

    private int clampScrollY(int y) {
        return Math.max(0, Math.min(y, maxScrollY()));
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int x = scroller.getCurrX();
            int y = scroller.getCurrY();
            if (x != getScrollX() || y != getScrollY()) {
                scrollTo(x, y);
            }
            postInvalidateOnAnimation();
        } else {
            super.computeScroll();
        }
    }

    void setCodeStyle(ThemeConfig.CodeStyle s) {
        style = s != null ? s : ThemeConfig.CODE_STYLES[0];
        setTextColor(style.plain);
        setBackgroundColor(style.paper);
        gutterPaint.setColor(style.gutter);
        gutterBgPaint.setColor(style.paper);
        currentLinePaint.setColor(withAlpha(style.gutter, 0x22));
        rehighlightAll();
        invalidate();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Replaces the whole buffer without polluting the undo stack (initial load). */
    void setInitialText(String text) {
        suppressWatcher = true;
        setText(text != null ? text : "");
        suppressWatcher = false;
        undoStack.clear();
        redoStack.clear();
        rehighlightAll();
        invalidateGutter();
    }

    // ---- undo / redo ----

    private void pushUndo(Edit e) {
        if (e.before.isEmpty() && e.after.isEmpty()) return;
        undoStack.addLast(e);
        while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
        redoStack.clear();
    }

    boolean canUndo() {
        return !undoStack.isEmpty();
    }

    boolean canRedo() {
        return !redoStack.isEmpty();
    }

    void undo() {
        if (viewOnly) return;
        Edit e = undoStack.pollLast();
        if (e == null) return;
        applyEdit(e.start, e.after.length(), e.before);
        redoStack.addLast(e);
        setSelectionSafe(e.start + e.before.length());
    }

    void redo() {
        if (viewOnly) return;
        Edit e = redoStack.pollLast();
        if (e == null) return;
        applyEdit(e.start, e.before.length(), e.after);
        undoStack.addLast(e);
        setSelectionSafe(e.start + e.after.length());
    }

    private void applyEdit(int start, int removeLen, String insert) {
        Editable ed = getText();
        if (ed == null) return;
        int end = Math.min(ed.length(), start + removeLen);
        if (start < 0 || start > ed.length()) return;
        suppressWatcher = true;
        ed.replace(start, end, insert);
        suppressWatcher = false;
        rehighlightAround(start, start + insert.length());
        invalidateGutter();
    }

    private void setSelectionSafe(int pos) {
        Editable ed = getText();
        if (ed == null) return;
        Selection.setSelection(ed, Math.max(0, Math.min(pos, ed.length())));
    }

    // ---- highlighting ----

    private void scheduleHighlight() {
        if (highlightPending) return;
        highlightPending = true;
        post(() -> {
            highlightPending = false;
            Editable ed = getText();
            if (ed == null) return;
            int caret = getSelectionStart();
            rehighlightAround(caret, caret);
        });
    }

    private void rehighlightAll() {
        Editable ed = getText();
        if (ed == null) return;
        if (ed.length() > LARGE_FILE_CHARS) {
            int caret = Math.max(0, getSelectionStart());
            rehighlightAround(caret, caret);
            return;
        }
        applySpans(ed, 0, ed.length());
    }

    /** Re-spans a window of lines around an edit rather than the whole file. */
    private void rehighlightAround(int start, int end) {
        Editable ed = getText();
        if (ed == null || ed.length() == 0) return;
        String text = ed.toString();
        int from = lineStartBefore(text, start, 40);
        int to = lineEndAfter(text, end, 40);
        applySpans(ed, from, to);
    }

    private void applySpans(Editable ed, int from, int to) {
        suppressWatcher = true;
        try {
            SyntaxHighlighter.applySpans((Spannable) ed, from, to, style);
        } finally {
            suppressWatcher = false;
        }
    }

    private static int lineStartBefore(String text, int index, int lines) {
        int i = Math.max(0, Math.min(index, text.length()));
        for (int n = 0; n <= lines; n++) {
            int nl = text.lastIndexOf('\n', i - 1);
            if (nl < 0) return 0;
            i = nl;
        }
        return Math.min(i + 1, text.length());
    }

    private static int lineEndAfter(String text, int index, int lines) {
        int i = Math.max(0, Math.min(index, text.length()));
        for (int n = 0; n <= lines; n++) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) return text.length();
            i = nl + 1;
        }
        return i;
    }

    // ---- gutter ----

    private void invalidateGutter() {
        int lines = getLineCount();
        int digits = Math.max(2, String.valueOf(Math.max(1, lines)).length());
        if (digits != gutterDigits) {
            gutterDigits = digits;
            gutterPaint.setTextSize(getTextSize() * 0.9f);
            float w = gutterPaint.measureText("0") * digits;
            gutterWidthPx = Math.round(w + dp(18));
            setPadding(gutterWidthPx + dp(10), getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null && gutterWidthPx > 0) {
            int scrollX = getScrollX();
            int scrollY = getScrollY();
            canvas.drawRect(scrollX, scrollY, scrollX + gutterWidthPx,
                    scrollY + getHeight(), gutterBgPaint);

            int caret = Math.max(0, getSelectionStart());
            int caretLine = layout.getLineForOffset(Math.min(caret, Math.max(0, length())));

            // Layout coordinates exclude the view's padding; TextView applies it when it
            // draws. Shift by the same amount so numbers line up with their lines.
            int padTop = getExtendedPaddingTop();
            int first = layout.getLineForVertical(Math.max(0, scrollY - padTop));
            int last = layout.getLineForVertical(Math.max(0, scrollY + getHeight() - padTop));
            gutterPaint.setTextSize(getTextSize() * 0.9f);

            CharSequence t = getText();
            // Count newlines up to the first visible row once, then walk forward — a
            // per-row recount would be O(text) per row on every frame.
            int logical = countNewlinesBefore(t, layout.getLineStart(first));
            for (int i = first; i <= last && i < layout.getLineCount(); i++) {
                int rowStart = layout.getLineStart(i);
                boolean startsLogicalLine = i == first
                        || (rowStart > 0 && t != null && rowStart <= t.length()
                                && t.charAt(rowStart - 1) == '\n');
                if (i > first && startsLogicalLine) logical++;

                if (i == caretLine) {
                    canvas.drawRect(scrollX, layout.getLineTop(i) + padTop, scrollX + getWidth(),
                            layout.getLineBottom(i) + padTop, currentLinePaint);
                }
                // Wrapped rows continue a logical line — number only the first row.
                if (startsLogicalLine) {
                    canvas.drawText(
                            String.format(Locale.US, "%d", logical + 1),
                            scrollX + gutterWidthPx - dp(9),
                            layout.getLineBaseline(i) + padTop,
                            gutterPaint);
                }
            }
        }
        super.onDraw(canvas);
    }

    private static int countNewlinesBefore(CharSequence t, int offset) {
        if (t == null) return 0;
        int limit = Math.min(offset, t.length());
        int count = 0;
        for (int i = 0; i < limit; i++) {
            if (t.charAt(i) == '\n') count++;
        }
        return count;
    }

    // ---- key handling: indent, auto-close ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (event.isShiftPressed()) {
                outdentSelection();
            } else {
                indentSelection();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed()) {
            if (autoIndentNewline()) return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && !hasSelection() && deleteIndentUnit()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Inserts a newline carrying the current line's indent, deepening after a block opener. */
    private boolean autoIndentNewline() {
        Editable ed = getText();
        if (ed == null) return false;
        int caret = getSelectionStart();
        if (caret < 0 || caret != getSelectionEnd()) return false;
        String text = ed.toString();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;

        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < caret && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        String beforeCaret = text.substring(lineStart, caret).trim();
        boolean opensBlock = beforeCaret.endsWith(":")
                || beforeCaret.endsWith("{")
                || beforeCaret.endsWith("(")
                || beforeCaret.endsWith("[");
        if (opensBlock) indent.append(INDENT);

        ed.replace(caret, caret, "\n" + indent);
        return true;
    }

    /** Backspace at the start of a line's text removes a whole indent unit. */
    private boolean deleteIndentUnit() {
        Editable ed = getText();
        if (ed == null) return false;
        int caret = getSelectionStart();
        if (caret <= 0) return false;
        String text = ed.toString();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        String prefix = text.substring(lineStart, caret);
        if (prefix.isEmpty() || prefix.trim().length() > 0) return false;
        int remove = prefix.length() % INDENT.length();
        if (remove == 0) remove = INDENT.length();
        remove = Math.min(remove, prefix.length());
        ed.replace(caret - remove, caret, "");
        return true;
    }

    private void indentSelection() {
        Editable ed = getText();
        if (ed == null) return;
        if (!hasSelection()) {
            int caret = getSelectionStart();
            ed.replace(caret, caret, INDENT);
            return;
        }
        forEachSelectedLine((start, lineText) -> {
            ed.replace(start, start, INDENT);
            return INDENT.length();
        });
    }

    private void outdentSelection() {
        Editable ed = getText();
        if (ed == null) return;
        forEachSelectedLine((start, lineText) -> {
            int strip = 0;
            while (strip < INDENT.length() && strip < lineText.length()
                    && lineText.charAt(strip) == ' ') {
                strip++;
            }
            if (strip == 0 && lineText.startsWith("\t")) strip = 1;
            if (strip > 0) ed.replace(start, start + strip, "");
            return -strip;
        });
    }

    private interface LineOp {
        /** @return character delta applied at this line, to keep later offsets correct */
        int apply(int lineStart, String lineText);
    }

    private void forEachSelectedLine(LineOp op) {
        Editable ed = getText();
        if (ed == null) return;
        int selStart = Math.min(getSelectionStart(), getSelectionEnd());
        int selEnd = Math.max(getSelectionStart(), getSelectionEnd());

        int first = 0;
        for (int i = Math.min(selStart, ed.length()) - 1; i >= 0; i--) {
            if (ed.charAt(i) == '\n') {
                first = i + 1;
                break;
            }
        }

        // Scan the Editable directly — copying the whole buffer per line would make
        // indenting a large selection quadratic.
        int cursor = first;
        int shift = 0;
        while (cursor <= selEnd + shift && cursor <= ed.length()) {
            int nl = -1;
            for (int i = cursor; i < ed.length(); i++) {
                if (ed.charAt(i) == '\n') {
                    nl = i;
                    break;
                }
            }
            int lineEnd = nl < 0 ? ed.length() : nl;
            int delta = op.apply(cursor, ed.subSequence(cursor, lineEnd).toString());
            shift += delta;
            if (nl < 0) break;
            cursor = nl + 1 + delta;
        }
        Selection.setSelection(ed,
                Math.max(0, Math.min(first, ed.length())),
                Math.max(0, Math.min(selEnd + shift, ed.length())));
    }

    // ---- find / replace ----

    /** @return character index of the match, or -1 */
    int findNext(String needle, int from, boolean caseSensitive) {
        if (needle == null || needle.isEmpty()) return -1;
        String hay = getText() != null ? getText().toString() : "";
        if (!caseSensitive) {
            hay = hay.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int at = hay.indexOf(needle, Math.max(0, from));
        if (at < 0) at = hay.indexOf(needle); // wrap
        return at;
    }

    int findPrevious(String needle, int before, boolean caseSensitive) {
        if (needle == null || needle.isEmpty()) return -1;
        String hay = getText() != null ? getText().toString() : "";
        if (!caseSensitive) {
            hay = hay.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int at = hay.lastIndexOf(needle, Math.max(0, before - 1));
        if (at < 0) at = hay.lastIndexOf(needle); // wrap
        return at;
    }

    int countMatches(String needle, boolean caseSensitive) {
        if (needle == null || needle.isEmpty()) return 0;
        String hay = getText() != null ? getText().toString() : "";
        if (!caseSensitive) {
            hay = hay.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int n = 0;
        int i = hay.indexOf(needle);
        while (i >= 0) {
            n++;
            i = hay.indexOf(needle, i + needle.length());
        }
        return n;
    }

    void selectRange(int start, int length) {
        Editable ed = getText();
        if (ed == null || start < 0) return;
        int end = Math.min(ed.length(), start + length);
        Selection.setSelection(ed, Math.min(start, ed.length()), end);
        bringPointIntoView(start);
    }

    /** Replaces the current selection, used by find/replace. */
    void replaceSelection(String replacement) {
        if (viewOnly) return;
        Editable ed = getText();
        if (ed == null || !hasSelection()) return;
        int s = Math.min(getSelectionStart(), getSelectionEnd());
        int e = Math.max(getSelectionStart(), getSelectionEnd());
        ed.replace(s, e, replacement != null ? replacement : "");
    }

    int replaceAll(String needle, String replacement, boolean caseSensitive) {
        if (viewOnly) return 0;
        Editable ed = getText();
        if (ed == null || needle == null || needle.isEmpty()) return 0;
        String hay = ed.toString();
        String cmp = caseSensitive ? hay : hay.toLowerCase(Locale.ROOT);
        String find = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
        String rep = replacement != null ? replacement : "";

        StringBuilder sb = new StringBuilder(hay.length());
        int i = 0;
        int count = 0;
        while (true) {
            int at = cmp.indexOf(find, i);
            if (at < 0) break;
            sb.append(hay, i, at).append(rep);
            i = at + find.length();
            count++;
        }
        if (count == 0) return 0;
        sb.append(hay.substring(i));

        int caret = getSelectionStart();
        setText(sb.toString());
        setSelectionSafe(Math.min(caret, sb.length()));
        rehighlightAll();
        invalidateGutter();
        return count;
    }

    void goToLine(int oneBasedLine) {
        Editable ed = getText();
        if (ed == null) return;
        String text = ed.toString();
        int line = Math.max(1, oneBasedLine);
        int pos = 0;
        for (int n = 1; n < line; n++) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                pos = text.length();
                break;
            }
            pos = nl + 1;
        }
        setSelectionSafe(pos);
        bringPointIntoView(pos);
    }

    void setSoftWrap(boolean wrap) {
        setHorizontallyScrolling(!wrap);
        requestLayout();
    }

    /**
     * View-only: scroll, select and copy still work; typing and undo edits do not.
     */
    void setViewOnly(boolean viewOnly) {
        this.viewOnly = viewOnly;
        setCursorVisible(!viewOnly);
        setLongClickable(true);
        setTextIsSelectable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        if (viewOnly) {
            setKeyListener(null);
        } else {
            setKeyListener(editKeyListener);
            setInputType(editInputType);
        }
    }

    boolean isViewOnly() {
        return viewOnly;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }
}
