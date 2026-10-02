package me.hapke.inkside;

/**
 * Fully local syntax highlighting (no bridge / network).
 * Fast mode: per-line tokenizer. Rich mode: multi-line state for
 * block comments and triple-quoted strings.
 */
final class SyntaxHighlighter {
    private SyntaxHighlighter() {}

    static void highlight(
            String[] lines,
            String[][] tokenTexts,
            int[][] tokenColors,
            ThemeConfig.CodeStyle style,
            boolean rich) {
        if (style == null) style = ThemeConfig.CODE_STYLES[0];
        if (lines == null) return;
        if (!rich) {
            for (int i = 0; i < lines.length; i++) {
                highlightLineFast(lines[i], i, tokenTexts, tokenColors, style);
            }
            return;
        }
        int state = STATE_NORMAL;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) {
                tokenTexts[i] = new String[0];
                tokenColors[i] = new int[0];
                continue;
            }
            int[] col = new int[line.length()];
            state = fillLineColorsRich(line, col, style, state);
            tokenTexts[i] = mergeRuns(line, col);
            tokenColors[i] = colorsForRuns(col, tokenTexts[i].length);
        }
    }

    private static final int STATE_NORMAL = 0;
    private static final int STATE_BLOCK_COMMENT = 1;
    private static final int STATE_TRIPLE_DQ = 2;
    private static final int STATE_TRIPLE_SQ = 3;

    /**
     * Applies colour spans to a live editor buffer, sharing the tokenizer the canvas uses.
     *
     * <p>Only the given character range is re-spanned, but tokenizing starts at
     * {@code fromLineStart} so multi-line constructs (block comments, triple-quoted
     * strings) resolve correctly.
     *
     * @param out          buffer to span; existing colour spans in range are replaced
     * @param regionStart  index to begin applying spans at (inclusive)
     * @param regionEnd    index to stop applying spans at (exclusive)
     */
    static void applySpans(
            android.text.Spannable out,
            int regionStart,
            int regionEnd,
            ThemeConfig.CodeStyle style) {
        if (out == null) return;
        if (style == null) style = ThemeConfig.CODE_STYLES[0];
        int len = out.length();
        regionStart = Math.max(0, Math.min(regionStart, len));
        regionEnd = Math.max(regionStart, Math.min(regionEnd, len));
        if (regionStart == regionEnd) return;

        for (android.text.style.ForegroundColorSpan s :
                out.getSpans(regionStart, regionEnd, android.text.style.ForegroundColorSpan.class)) {
            out.removeSpan(s);
        }

        String text = out.toString();
        // Re-tokenize from the start of the containing line so state is correct.
        int lineStart = text.lastIndexOf('\n', Math.max(0, regionStart - 1)) + 1;
        int state = STATE_NORMAL;
        int pos = lineStart;
        while (pos < regionEnd) {
            int nl = text.indexOf('\n', pos);
            int lineEnd = nl < 0 ? len : nl;
            int lineLen = lineEnd - pos;
            if (lineLen > 0) {
                int[] col = new int[lineLen];
                state = fillLineColorsRich(text.substring(pos, lineEnd), col, style, state);
                applyRuns(out, pos, col, regionStart, regionEnd);
            }
            if (nl < 0) break;
            pos = nl + 1;
        }
    }

    /** Coalesces equal-coloured runs into single spans to keep span count low. */
    private static void applyRuns(
            android.text.Spannable out, int base, int[] col, int clipStart, int clipEnd) {
        int i = 0;
        while (i < col.length) {
            int j = i + 1;
            while (j < col.length && col[j] == col[i]) j++;
            int from = Math.max(base + i, clipStart);
            int to = Math.min(base + j, clipEnd);
            if (col[i] != 0 && from < to) {
                out.setSpan(
                        new android.text.style.ForegroundColorSpan(col[i]),
                        from, to,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
    }

    private static void highlightLineFast(
            String line,
            int index,
            String[][] tokenTexts,
            int[][] tokenColors,
            ThemeConfig.CodeStyle style) {
        if (line == null || line.isEmpty()) {
            tokenTexts[index] = new String[0];
            tokenColors[index] = new int[0];
            return;
        }
        int[] col = new int[line.length()];
        fillLineColorsRich(line, col, style, STATE_NORMAL);
        tokenTexts[index] = mergeRuns(line, col);
        tokenColors[index] = colorsForRuns(col, tokenTexts[index].length);
    }

    private static int fillLineColorsRich(
            String line, int[] col, ThemeConfig.CodeStyle style, int state) {
        int i = 0;
        int n = line.length();

        while (i < n) {
            if (state == STATE_BLOCK_COMMENT) {
                int start = i;
                while (i + 1 < n) {
                    if (line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                        i += 2;
                        for (int c = start; c < i; c++) col[c] = style.comment;
                        state = STATE_NORMAL;
                        break;
                    }
                    i++;
                }
                if (state == STATE_BLOCK_COMMENT) {
                    for (int c = start; c < n; c++) col[c] = style.comment;
                    return state;
                }
                continue;
            }
            if (state == STATE_TRIPLE_DQ || state == STATE_TRIPLE_SQ) {
                char q = state == STATE_TRIPLE_DQ ? '"' : '\'';
                int start = i;
                while (i < n) {
                    if (i + 2 < n
                            && line.charAt(i) == q
                            && line.charAt(i + 1) == q
                            && line.charAt(i + 2) == q) {
                        i += 3;
                        for (int c = start; c < i; c++) col[c] = style.string;
                        state = STATE_NORMAL;
                        break;
                    }
                    if (line.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    i++;
                }
                if (state != STATE_NORMAL) {
                    for (int c = start; c < n; c++) col[c] = style.string;
                    return state;
                }
                continue;
            }

            char ch = line.charAt(i);
            if (Character.isWhitespace(ch)) {
                col[i++] = style.plain;
                continue;
            }

            // Block comment /*
            if (ch == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                boolean closed = false;
                while (i + 1 < n) {
                    if (line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                        i += 2;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    for (int c = start; c < n; c++) col[c] = style.comment;
                    return STATE_BLOCK_COMMENT;
                }
                for (int c = start; c < i; c++) col[c] = style.comment;
                continue;
            }

            // Line comments
            if (ch == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                for (int c = i; c < n; c++) col[c] = style.comment;
                return STATE_NORMAL;
            }
            if (ch == '#') {
                for (int c = i; c < n; c++) col[c] = style.comment;
                return STATE_NORMAL;
            }

            // Triple-quoted strings
            if ((ch == '"' || ch == '\'') && i + 2 < n
                    && line.charAt(i + 1) == ch && line.charAt(i + 2) == ch) {
                char q = ch;
                int start = i;
                i += 3;
                boolean closed = false;
                while (i + 2 < n) {
                    if (line.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (line.charAt(i) == q
                            && line.charAt(i + 1) == q
                            && line.charAt(i + 2) == q) {
                        i += 3;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    for (int c = start; c < n; c++) col[c] = style.string;
                    return q == '"' ? STATE_TRIPLE_DQ : STATE_TRIPLE_SQ;
                }
                for (int c = start; c < i; c++) col[c] = style.string;
                continue;
            }

            // Single / double / backtick strings
            if (ch == '"' || ch == '\'' || ch == '`') {
                char q = ch;
                int start = i++;
                while (i < n) {
                    char c = line.charAt(i++);
                    if (c == '\\' && i < n) {
                        i++;
                        continue;
                    }
                    if (c == q) break;
                }
                for (int c = start; c < i; c++) col[c] = style.string;
                continue;
            }

            // Numbers
            if (Character.isDigit(ch)
                    || (ch == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int start = i++;
                while (i < n) {
                    char c = line.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == '_'
                            || c == 'x' || c == 'X'
                            || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                        i++;
                    } else break;
                }
                for (int c = start; c < i; c++) col[c] = style.number;
                continue;
            }

            // Identifiers / keywords / builtins
            if (Character.isLetter(ch) || ch == '_') {
                int start = i++;
                while (i < n) {
                    char c = line.charAt(i);
                    if (Character.isLetterOrDigit(c) || c == '_') i++;
                    else break;
                }
                String word = line.substring(start, i);
                int color = style.plain;
                if (isKeyword(word)) color = style.keyword;
                else if (isBuiltin(word)) color = style.keyword;
                for (int c = start; c < i; c++) col[c] = color;
                continue;
            }

            // Decorators @name
            if (ch == '@' && i + 1 < n && (Character.isLetter(line.charAt(i + 1))
                    || line.charAt(i + 1) == '_')) {
                int start = i++;
                while (i < n) {
                    char c = line.charAt(i);
                    if (Character.isLetterOrDigit(c) || c == '_' || c == '.') i++;
                    else break;
                }
                for (int c = start; c < i; c++) col[c] = style.keyword;
                continue;
            }

            col[i++] = style.plain;
        }
        return STATE_NORMAL;
    }

    private static String[] mergeRuns(String line, int[] col) {
        if (line.isEmpty()) return new String[0];
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        int start = 0;
        int cur = col[0];
        for (int i = 1; i < line.length(); i++) {
            if (col[i] != cur) {
                parts.add(line.substring(start, i));
                start = i;
                cur = col[i];
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    private static int[] colorsForRuns(int[] col, int runCount) {
        int[] colors = new int[runCount];
        if (col.length == 0 || runCount == 0) return colors;
        int run = 0;
        int cur = col[0];
        for (int i = 1; i < col.length; i++) {
            if (col[i] != cur) {
                colors[run++] = cur;
                cur = col[i];
            }
        }
        if (run < colors.length) colors[run] = cur;
        return colors;
    }

    private static boolean isBuiltin(String s) {
        switch (s) {
            case "print": case "len": case "range": case "str": case "int": case "float":
            case "list": case "dict": case "set": case "tuple": case "bool": case "type":
            case "super": case "isinstance": case "hasattr": case "getattr": case "open":
            case "console": case "Math": case "Array": case "Object": case "JSON":
            case "Promise": case "Map": case "Set": case "Error":
                return true;
            default:
                return false;
        }
    }

    private static boolean isKeyword(String s) {
        switch (s) {
            case "def": case "class": case "return": case "if": case "elif": case "else":
            case "for": case "while": case "import": case "from": case "as": case "with":
            case "try": case "except": case "finally": case "raise": case "yield":
            case "lambda": case "pass": case "break": case "continue": case "in": case "is":
            case "not": case "and": case "or": case "None": case "True": case "False":
            case "async": case "await": case "global": case "nonlocal": case "assert":
            case "del": case "function": case "const": case "let": case "var": case "new":
            case "this": case "typeof": case "instanceof": case "switch": case "case":
            case "default": case "throw": case "catch": case "void": case "null":
            case "undefined": case "fun": case "val": case "when": case "object":
            case "interface": case "package": case "public": case "private":
            case "protected": case "static": case "final": case "override": case "open":
            case "data": case "sealed": case "extends": case "implements": case "super":
            case "export": case "of": case "do": case "then":
                return true;
            default:
                return false;
        }
    }
}
