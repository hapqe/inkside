package me.hapke.inkside;

/**
 * Single appearance system: each app theme carries matching chrome + syntax colors.
 * Chat, editor, and canvas all read from the same palette.
 */
final class ThemeConfig {

    static final class CodeStyle {
        final String id;
        final String label;
        final int paper;
        final int gutter;
        final int title;
        final int grid;
        final int keyword;
        final int string;
        final int number;
        final int comment;
        final int plain;

        CodeStyle(
                String id,
                String label,
                int paper,
                int gutter,
                int title,
                int grid,
                int keyword,
                int string,
                int number,
                int comment,
                int plain) {
            this.id = id;
            this.label = label;
            this.paper = paper;
            this.gutter = gutter;
            this.title = title;
            this.grid = grid;
            this.keyword = keyword;
            this.string = string;
            this.number = number;
            this.comment = comment;
            this.plain = plain;
        }
    }

    static final class AppTheme {
        final String id;
        final String label;
        final boolean light;
        final int surface;
        final int surfaceContainer;
        final int surfaceContainerHigh;
        final int surfaceContainerHighest;
        final int onSurface;
        final int onSurfaceVariant;
        final int primary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int secondaryContainer;
        final int outlineVariant;
        final int canvasBg;
        /** Chat bubble fills — tuned for contrast with {@link #onSurface}. */
        final int chatYouBg;
        final int chatAgentBg;
        final int chatWarnBg;
        final int chatErrorBg;
        final CodeStyle code;

        AppTheme(
                String id,
                String label,
                boolean light,
                int surface,
                int surfaceContainer,
                int surfaceContainerHigh,
                int surfaceContainerHighest,
                int onSurface,
                int onSurfaceVariant,
                int primary,
                int primaryContainer,
                int onPrimaryContainer,
                int secondaryContainer,
                int outlineVariant,
                int canvasBg,
                int chatYouBg,
                int chatAgentBg,
                int chatWarnBg,
                int chatErrorBg,
                CodeStyle code) {
            this.id = id;
            this.label = label;
            this.light = light;
            this.surface = surface;
            this.surfaceContainer = surfaceContainer;
            this.surfaceContainerHigh = surfaceContainerHigh;
            this.surfaceContainerHighest = surfaceContainerHighest;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.primary = primary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.secondaryContainer = secondaryContainer;
            this.outlineVariant = outlineVariant;
            this.canvasBg = canvasBg;
            this.chatYouBg = chatYouBg;
            this.chatAgentBg = chatAgentBg;
            this.chatWarnBg = chatWarnBg;
            this.chatErrorBg = chatErrorBg;
            this.code = code;
        }
    }

    /**
     * Inkside Paper, Sage and Rose (the website's; Paper is the default), then
     * Moss, Ink, Rosé Pine (main / moon / dawn), Tokyo Night, Catppuccin Mocha,
     * GitHub Dark and Daylight, then Nord, Dracula, Gruvbox, One Dark, Solarized,
     * Kanagawa, Everforest, Ayu Mirage, Monokai Pro, Synthwave, Espresso, Midnight OLED
     * (dark) and Catppuccin Latte, GitHub Light, Solarized Light, Gruvbox Light,
     * Everforest Light, Sakura and Mint (light). {@code APP_THEMES[0]} is the default
     * wherever no theme is chosen; everything else refers to themes by id.
     * Legacy ids ({@code midnight}, {@code ink}, {@code tokenite}, …) map in {@link #appThemeById}.
     */
    static final AppTheme[] APP_THEMES = {
            // The three website themes (inkside.hapke.me) come first, and Inkside Paper is the
            // default: warm paper with navy ink and a blue accent, sage with a green one, rose
            // with a pink one. They switch on the website's border-and-hard-shadow look
            // (SketchStyle).
            new AppTheme(
                    "inkside-paper", "Inkside Paper", true,
                    0xFFF5F1E8, 0xFFEFEADC, 0xE6ECE6D6, 0xFFDDD5C1,
                    0xFF1B1F3B, 0xFF5A5E7A, 0xFF3B5BDB, 0xFFDBE4FF, 0xFF1A2C80,
                    0xFFECE6D6, 0xFFCFC6AE, 0xFFEFEADC,
                    0xFFDBE4FF, 0xFFECE6D6, 0xFFFFF3BF, 0xFFFFD8C2,
                    codeLight("inkside-paper", 0xFFFFFDF7, 0xFF3B5BDB, 0xFF2B8A3E, 0xFFD9480F, 0xFF7C7F96, 0xFF1B1F3B)),
            new AppTheme(
                    "inkside-sage", "Inkside Sage", true,
                    0xFFE8EFE3, 0xFFDFE8D9, 0xE6D9E4D1, 0xFFCBD8C1,
                    0xFF1F3325, 0xFF526A58, 0xFF2B8A3E, 0xFFC3EBCB, 0xFF0E3A18,
                    0xFFD9E4D1, 0xFFB9C9AE, 0xFFDFE8D9,
                    0xFFCDEBD3, 0xFFD9E4D1, 0xFFF3EFB8, 0xFFF4D5B8,
                    codeLight("inkside-sage", 0xFFF6FAF3, 0xFFC2410C, 0xFF2B8A3E, 0xFF1F6FB2, 0xFF7A8F7F, 0xFF1F3325)),
            new AppTheme(
                    "inkside-rose", "Inkside Rose", true,
                    0xFFFBEEF0, 0xFFF7E5E9, 0xE6F4DDE2, 0xFFEACCD4,
                    0xFF3A1A26, 0xFF7D5464, 0xFFD6336C, 0xFFFFD0DC, 0xFF5A0B2C,
                    0xFFF4DDE2, 0xFFE0BFC9, 0xFFF7E5E9,
                    0xFFFFD6E2, 0xFFF4DDE2, 0xFFFFF0C2, 0xFFF9CFCF,
                    codeLight("inkside-rose", 0xFFFFF8F9, 0xFFD6336C, 0xFF2E7D5B, 0xFF7048E8, 0xFF9A7884, 0xFF3A1A26)),
            // Moss — deep green-black, mint accent (the reference: a saturated, deep
            // primaryContainer under a very light on-colour, so selected icons pop).
            new AppTheme(
                    "matcha", "Moss", false,
                    0xFF0A0F0C, 0xFF121A15, 0xE61A2420, 0xFF24302A,
                    0xFFECFDF5, 0xFF8BA399, 0xFF6EE7B7, 0xFF065F46, 0xFFD1FAE5,
                    0xFF2D3B34, 0xFF3F4F46, 0xFF07100C,
                    0xFF064E3B, 0xFF24302A, 0xFF422006, 0xFF4C0519,
                    codeDark("matcha", 0xFF0F1712, 0xFF6EE7B7, 0xFFFDE68A, 0xFFFDA4AF, 0xFF6B7280, 0xFFECFDF5)),
            // Ink — true black, warm greys. Selected state used to be near-black on black;
            // now a deep bronze container with a cream on-colour.
            new AppTheme(
                    "ink", "Ink", false,
                    0xFF000000, 0xFF141414, 0xE61F1F1F, 0xFF2C2C2C,
                    0xFFF5F4F1, 0xFFB3AFA7, 0xFFE8D2AE, 0xFF5C4526, 0xFFFFF1DC,
                    0xFF2A2A2A, 0xFF4A4A4A, 0xFF000000,
                    0xFF4A3920, 0xFF1C1C1C, 0xFF3A3420, 0xFF3F1E1E,
                    codeDark("ink", 0xFF000000,
                            0xFFE3C08D,  // keyword — warm sand
                            0xFFA5D6A7,  // string — soft green
                            0xFFF4B183,  // number — peach
                            0xFF8A857E,  // comment — muted, still legible
                            0xFFF2F0EC)), // plain — near-white
            // Rosé Pine — https://rosepinetheme.com. primaryContainer was identical to the
            // pill colour, so selections disappeared; now a deep iris.
            new AppTheme(
                    "rose-pine", "Rosé Pine", false,
                    0xFF191724, 0xFF1F1D2E, 0xE626233A, 0xFF403D52,
                    0xFFE0DEF4, 0xFFA9A5C3, 0xFFC4A7E7, 0xFF54407A, 0xFFF3E9FF,
                    0xFF2E2B42, 0xFF524F67, 0xFF16141F,
                    0xFF2F5E73, 0xFF26233A, 0xFF403D2A, 0xFF4A2030,
                    codeDark("rose-pine", 0xFF1F1D2E, 0xFFC4A7E7, 0xFF9CCFD8, 0xFFEBBCBA, 0xFF7E7A98, 0xFFE0DEF4)),
            new AppTheme(
                    "rose-pine-moon", "Rosé Pine Moon", false,
                    0xFF232136, 0xFF2A273F, 0xE6393552, 0xFF44415A,
                    0xFFE0DEF4, 0xFFAAA6C4, 0xFFC4A7E7, 0xFF6A4F9C, 0xFFF3E9FF,
                    0xFF393552, 0xFF56526E, 0xFF1E1C2E,
                    0xFF2F6A85, 0xFF393552, 0xFF403D2A, 0xFF4A2030,
                    codeDark("rose-pine-moon", 0xFF2A273F, 0xFFC4A7E7, 0xFF9CCFD8, 0xFFEA9A97, 0xFF7E7A98, 0xFFE0DEF4)),
            // Rosé Pine Dawn — light. Accent and secondary text darkened for contrast on
            // the cream paper (the old iris was ~3:1).
            new AppTheme(
                    "rose-pine-dawn", "Rosé Pine Dawn", true,
                    0xFFFAF4ED, 0xFFFFFAF3, 0xE6F2E9E1, 0xFFE4DDD8,
                    0xFF464261, 0xFF6A6685, 0xFF7456A0, 0xFFD4B8F0, 0xFF3D2A5C,
                    0xFFF2E9E1, 0xFFC9C1C4, 0xFFF4EDE8,
                    0xFFE6D3F7, 0xFFF2E9E1, 0xFFF5E6C8, 0xFFF2D5DA,
                    codeLight("rose-pine-dawn", 0xFFFFFAF3, 0xFF7456A0, 0xFF3E7C86, 0xFFB4635F, 0xFF8A859A, 0xFF464261)),
            // Tokyo Night — navy with a clear blue accent.
            new AppTheme(
                    "tokyo-night", "Tokyo Night", false,
                    0xFF1A1B26, 0xFF1F2335, 0xE624283B, 0xFF2F3549,
                    0xFFD5DCF7, 0xFFA3ACD4, 0xFF7AA2F7, 0xFF2D5099, 0xFFDDE7FF,
                    0xFF292E42, 0xFF3B4261, 0xFF16161E,
                    0xFF23407E, 0xFF24283B, 0xFF3D3420, 0xFF45212B,
                    codeDark("tokyo-night", 0xFF1F2335, 0xFFBB9AF7, 0xFF9ECE6A, 0xFFFF9E64, 0xFF6A739C, 0xFFD5DCF7)),
            // Catppuccin Mocha — soft pastel on warm charcoal.
            new AppTheme(
                    "catppuccin", "Catppuccin Mocha", false,
                    0xFF1E1E2E, 0xFF232336, 0xE62A2B3D, 0xFF313244,
                    0xFFCDD6F4, 0xFFAAB1CC, 0xFFCBA6F7, 0xFF5B3F8C, 0xFFF3E8FF,
                    0xFF313244, 0xFF45475A, 0xFF181825,
                    0xFF4A3872, 0xFF313244, 0xFF3E3524, 0xFF4A2230,
                    codeDark("catppuccin", 0xFF181825, 0xFFCBA6F7, 0xFFA6E3A1, 0xFFFAB387, 0xFF7F849C, 0xFFCDD6F4)),
            // GitHub Dark — neutral near-black, crisp blue accent.
            new AppTheme(
                    "github-dark", "GitHub Dark", false,
                    0xFF0D1117, 0xFF161B22, 0xE621262D, 0xFF2D333B,
                    0xFFE6EDF3, 0xFFA3ADB9, 0xFF58A6FF, 0xFF1B5099, 0xFFD6E9FF,
                    0xFF21262D, 0xFF3D444D, 0xFF010409,
                    0xFF123A6B, 0xFF21262D, 0xFF3B2E12, 0xFF4A1A1F,
                    codeDark("github-dark", 0xFF161B22, 0xFFFF7B72, 0xFFA5D6FF, 0xFF79C0FF, 0xFF8B949E, 0xFFE6EDF3)),
            // Daylight — clean light theme, indigo accent, high-contrast text.
            new AppTheme(
                    "daylight", "Daylight", true,
                    0xFFFAFAFB, 0xFFF3F3F6, 0xE6ECECF1, 0xFFE2E2EA,
                    0xFF1B1B1F, 0xFF53535E, 0xFF4F46E5, 0xFFC3CAFF, 0xFF1E1B4B,
                    0xFFECECF1, 0xFFCFCFDA, 0xFFF0F0F3,
                    0xFFE0E4FF, 0xFFEFEFF3, 0xFFFEF3C7, 0xFFFEE2E2,
                    codeLight("daylight", 0xFFFFFFFF, 0xFF6D28D9, 0xFF047857, 0xFFB45309, 0xFF6B7280, 0xFF1F2937)),
            // ---- More themes: well-known modern palettes (dark first, then light) ----
            new AppTheme(
                    "nord", "Nord", false,
                    0xFF2E3440, 0xFF343B49, 0xE63B4252, 0xFF434C5E,
                    0xFFECEFF4, 0xFFB4BCCB, 0xFF88C0D0, 0xFF3B6272, 0xFFDDF3F9,
                    0xFF3B4252, 0xFF4C566A, 0xFF272C36,
                    0xFF345A6B, 0xFF3B4252, 0xFF4A4330, 0xFF4F2F35,
                    codeDark("nord", 0xFF2E3440, 0xFF81A1C1, 0xFFA3BE8C, 0xFFB48EAD, 0xFF7B88A1, 0xFFD8DEE9)),
            new AppTheme(
                    "dracula", "Dracula", false,
                    0xFF282A36, 0xFF2F3241, 0xE6343746, 0xFF44475A,
                    0xFFF8F8F2, 0xFFB8BAD0, 0xFFBD93F9, 0xFF5B3F8F, 0xFFF1E7FF,
                    0xFF343746, 0xFF565A73, 0xFF21222C,
                    0xFF4B3878, 0xFF343746, 0xFF4A4126, 0xFF4F2733,
                    codeDark("dracula", 0xFF282A36, 0xFFFF79C6, 0xFFF1FA8C, 0xFFBD93F9, 0xFF7B87B8, 0xFFF8F8F2)),
            new AppTheme(
                    "gruvbox-dark", "Gruvbox Dark", false,
                    0xFF282828, 0xFF32302F, 0xE63C3836, 0xFF504945,
                    0xFFEBDBB2, 0xFFBDAE93, 0xFFFABD2F, 0xFF7A5B0E, 0xFFFFF0C2,
                    0xFF3C3836, 0xFF665C54, 0xFF1D2021,
                    0xFF6A4E10, 0xFF3C3836, 0xFF4A3B1A, 0xFF5A2620,
                    codeDark("gruvbox-dark", 0xFF282828, 0xFFFB4934, 0xFFB8BB26, 0xFFD3869B, 0xFF928374, 0xFFEBDBB2)),
            new AppTheme(
                    "one-dark", "One Dark", false,
                    0xFF282C34, 0xFF2C313A, 0xE6333842, 0xFF3E4451,
                    0xFFDCDFE4, 0xFFA0A8B7, 0xFF61AFEF, 0xFF25558A, 0xFFDCEEFF,
                    0xFF333842, 0xFF4B5263, 0xFF21252B,
                    0xFF23507F, 0xFF333842, 0xFF463C24, 0xFF4D2B31,
                    codeDark("one-dark", 0xFF282C34, 0xFFC678DD, 0xFF98C379, 0xFFD19A66, 0xFF7F8898, 0xFFABB2BF)),
            new AppTheme(
                    "solarized-dark", "Solarized Dark", false,
                    0xFF002B36, 0xFF073642, 0xE60B3C49, 0xFF15495A,
                    0xFFEEE8D5, 0xFF93A1A1, 0xFF2AA198, 0xFF0F5A57, 0xFFD4F5F0,
                    0xFF0B3C49, 0xFF2A5866, 0xFF00222B,
                    0xFF0E5350, 0xFF0B3C49, 0xFF4A3F0E, 0xFF4C2226,
                    codeDark("solarized-dark", 0xFF002B36, 0xFF859900, 0xFF2AA198, 0xFFCB4B16, 0xFF7A9299, 0xFFA8B8B8)),
            new AppTheme(
                    "kanagawa", "Kanagawa", false,
                    0xFF1F1F28, 0xFF25252F, 0xE62A2A37, 0xFF363646,
                    0xFFDCD7BA, 0xFFA6A28C, 0xFF7E9CD8, 0xFF34508F, 0xFFE2EAFF,
                    0xFF2A2A37, 0xFF54546D, 0xFF16161D,
                    0xFF2D4373, 0xFF2A2A37, 0xFF463F25, 0xFF4B2530,
                    codeDark("kanagawa", 0xFF1F1F28, 0xFF957FB8, 0xFF98BB6C, 0xFFFFA066, 0xFF8A8874, 0xFFDCD7BA)),
            new AppTheme(
                    "everforest", "Everforest", false,
                    0xFF2D353B, 0xFF343F44, 0xE63A464C, 0xFF475258,
                    0xFFD3C6AA, 0xFF9DA9A0, 0xFFA7C080, 0xFF4B5F2E, 0xFFE9F5D3,
                    0xFF3A464C, 0xFF56635F, 0xFF232A2E,
                    0xFF43562A, 0xFF3A464C, 0xFF4B4326, 0xFF4F2D2D,
                    codeDark("everforest", 0xFF2D353B, 0xFFE67E80, 0xFFA7C080, 0xFFD699B6, 0xFF859289, 0xFFD3C6AA)),
            new AppTheme(
                    "ayu-mirage", "Ayu Mirage", false,
                    0xFF1F2430, 0xFF242A38, 0xE62A3142, 0xFF343C50,
                    0xFFCCCAC2, 0xFF9BA3B8, 0xFFFFA759, 0xFF8A4A10, 0xFFFFE8D0,
                    0xFF2A3142, 0xFF444C62, 0xFF191E2A,
                    0xFF7A4210, 0xFF2A3142, 0xFF4A3F1F, 0xFF4E2A30,
                    codeDark("ayu-mirage", 0xFF1F2430, 0xFFFFA759, 0xFFD5FF80, 0xFFDFBFFF, 0xFF8A93A8, 0xFFCCCAC2)),
            new AppTheme(
                    "monokai-pro", "Monokai Pro", false,
                    0xFF2D2A2E, 0xFF353236, 0xE63B383D, 0xFF4A474D,
                    0xFFFCFCFA, 0xFFB9B7BA, 0xFFFF6188, 0xFF8A2A46, 0xFFFFE1E8,
                    0xFF3B383D, 0xFF5B595C, 0xFF221F22,
                    0xFF7A2640, 0xFF3B383D, 0xFF4B4224, 0xFF52252D,
                    codeDark("monokai-pro", 0xFF2D2A2E, 0xFFFF6188, 0xFFFFD866, 0xFFAB9DF2, 0xFF908E92, 0xFFFCFCFA)),
            new AppTheme(
                    "synthwave", "Synthwave", false,
                    0xFF1B1035, 0xFF221541, 0xE62A1B4E, 0xFF352461,
                    0xFFF4E9FF, 0xFFB9A6D9, 0xFFFF5CD1, 0xFF8A1C74, 0xFFFFE0F6,
                    0xFF2A1B4E, 0xFF4A3980, 0xFF150C2B,
                    0xFF7A1C68, 0xFF2A1B4E, 0xFF4A3B1E, 0xFF551C34,
                    codeDark("synthwave", 0xFF1B1035, 0xFFFF5CD1, 0xFF72F1B8, 0xFFFEDE5D, 0xFF8F7DB8, 0xFFF4E9FF)),
            new AppTheme(
                    "espresso", "Espresso", false,
                    0xFF1F1713, 0xFF281E19, 0xE633261F, 0xFF40332B,
                    0xFFF3E7DC, 0xFFC0AC9C, 0xFFE3A86B, 0xFF7A4B1C, 0xFFFFEAD3,
                    0xFF33261F, 0xFF5A4739, 0xFF171009,
                    0xFF6A4118, 0xFF33261F, 0xFF4A3B1C, 0xFF52261F,
                    codeDark("espresso", 0xFF1F1713, 0xFFE3A86B, 0xFFA9C28B, 0xFFE08F7A, 0xFF9A8676, 0xFFF3E7DC)),
            new AppTheme(
                    "midnight-oled", "Midnight OLED", false,
                    0xFF000000, 0xFF0D0D10, 0xE6151519, 0xFF202027,
                    0xFFF2F2F5, 0xFFA4A4B0, 0xFF5B9DFF, 0xFF17408F, 0xFFDDE9FF,
                    0xFF1A1A20, 0xFF35353F, 0xFF000000,
                    0xFF14357A, 0xFF1A1A20, 0xFF3A3115, 0xFF451A20,
                    codeDark("midnight-oled", 0xFF000000, 0xFFC08BFF, 0xFF6EE7A0, 0xFFFFA36B, 0xFF7C7C89, 0xFFF2F2F5)),
            new AppTheme(
                    "catppuccin-latte", "Catppuccin Latte", true,
                    0xFFEFF1F5, 0xFFE6E9EF, 0xE6DCE0E8, 0xFFCCD0DA,
                    0xFF4C4F69, 0xFF5C5F77, 0xFF8839EF, 0xFFE2CFFB, 0xFF3E1670,
                    0xFFE6E9EF, 0xFFBCC0CC, 0xFFE6E9EF,
                    0xFFE5D6FA, 0xFFE6E9EF, 0xFFF7E3BF, 0xFFF6D0D6,
                    codeLight("catppuccin-latte", 0xFFF8F9FB, 0xFF8839EF, 0xFF2F7D1F, 0xFFC24E0A, 0xFF7C7F93, 0xFF4C4F69)),
            new AppTheme(
                    "github-light", "GitHub Light", true,
                    0xFFFFFFFF, 0xFFF6F8FA, 0xE6EAEEF2, 0xFFD8DEE4,
                    0xFF1F2328, 0xFF59636E, 0xFF0969DA, 0xFFCFE4FF, 0xFF0A3069,
                    0xFFEAEEF2, 0xFFD0D7DE, 0xFFF3F5F8,
                    0xFFDDEBFF, 0xFFEEF1F4, 0xFFFFF1C2, 0xFFFFDCD7,
                    codeLight("github-light", 0xFFFFFFFF, 0xFFCF222E, 0xFF0A3069, 0xFF0550AE, 0xFF6E7781, 0xFF1F2328)),
            new AppTheme(
                    "solarized-light", "Solarized Light", true,
                    0xFFFDF6E3, 0xFFF5EED9, 0xE6EEE8D5, 0xFFE4DDC6,
                    0xFF3D4F55, 0xFF586E75, 0xFF1B6FA8, 0xFFCDE4F3, 0xFF0B3B5C,
                    0xFFEEE8D5, 0xFFD3CBB0, 0xFFF3ECD6,
                    0xFFD4E7F4, 0xFFEEE8D5, 0xFFF6E3A8, 0xFFF3D0C8,
                    codeLight("solarized-light", 0xFFFDF6E3, 0xFF6E7F00, 0xFF1F7F78, 0xFFB8400F, 0xFF7F9096, 0xFF3D4F55)),
            new AppTheme(
                    "gruvbox-light", "Gruvbox Light", true,
                    0xFFFBF1C7, 0xFFF2E5BC, 0xE6EBDBB2, 0xFFD5C4A1,
                    0xFF3C3836, 0xFF665C54, 0xFFAF3A03, 0xFFF8D7A8, 0xFF5C1E00,
                    0xFFEBDBB2, 0xFFBDAE93, 0xFFF0E3B5,
                    0xFFF6DBB0, 0xFFEBDBB2, 0xFFF3D98A, 0xFFF1C4B8,
                    codeLight("gruvbox-light", 0xFFFBF1C7, 0xFF9D0006, 0xFF79740E, 0xFF8F3F71, 0xFF7C6F64, 0xFF3C3836)),
            new AppTheme(
                    "everforest-light", "Everforest Light", true,
                    0xFFF9F3DC, 0xFFF0EAD2, 0xE6EAE4CB, 0xFFDDD8BE,
                    0xFF3F4E4F, 0xFF5F6E68, 0xFF56731A, 0xFFDDE7B8, 0xFF2B3D0A,
                    0xFFEAE4CB, 0xFFC9C4A8, 0xFFEFE8CF,
                    0xFFE0E9BE, 0xFFEAE4CB, 0xFFF3DE9A, 0xFFF1CBC0,
                    codeLight("everforest-light", 0xFFF9F3DC, 0xFFB23E48, 0xFF56731A, 0xFF9A5B8C, 0xFF7D857C, 0xFF4B5852)),
            new AppTheme(
                    "sakura", "Sakura", true,
                    0xFFFFF5F7, 0xFFFBEBEF, 0xE6F6E0E6, 0xFFEFD3DC,
                    0xFF3D2A31, 0xFF6F5560, 0xFFC2185B, 0xFFFAD1DF, 0xFF5A0B2C,
                    0xFFF6E3E9, 0xFFE3C4CF, 0xFFFAEBEF,
                    0xFFFBD9E5, 0xFFF6E3E9, 0xFFF7E1B0, 0xFFF4C9C9,
                    codeLight("sakura", 0xFFFFF9FA, 0xFFC2185B, 0xFF2E7D5B, 0xFFB5541C, 0xFF95777F, 0xFF3D2A31)),
            new AppTheme(
                    "mint", "Mint", true,
                    0xFFF3FAF7, 0xFFE8F4EF, 0xE6DDEEE7, 0xFFC9E1D8,
                    0xFF16302A, 0xFF4A6860, 0xFF0F766E, 0xFFBFEBDD, 0xFF053B36,
                    0xFFE3F1EB, 0xFFB5D0C6, 0xFFE9F4EF,
                    0xFFCBEFE3, 0xFFE3F1EB, 0xFFF6E5A6, 0xFFF3CFCB,
                    codeLight("mint", 0xFFFAFDFB, 0xFF0F766E, 0xFF3B7A2B, 0xFFB4501A, 0xFF7B968D, 0xFF16302A)),
    };

    /** @deprecated Prefer {@link AppTheme#code}; kept for call sites that indexed the old array. */
    static final CodeStyle[] CODE_STYLES = {
            APP_THEMES[0].code,
            APP_THEMES[1].code,
            APP_THEMES[2].code,
            APP_THEMES[3].code,
            APP_THEMES[4].code,
    };

    private static CodeStyle codeDark(
            String id, int paper, int keyword, int string, int number, int comment, int plain) {
        return new CodeStyle(
                id, id,
                paper, comment, comment, 0x14FFFFFF,
                keyword, string, number, comment, plain);
    }

    private static CodeStyle codeLight(
            String id, int paper, int keyword, int string, int number, int comment, int plain) {
        return new CodeStyle(
                id, id,
                paper, comment, comment, 0x14000000,
                keyword, string, number, comment, plain);
    }

    static AppTheme appThemeById(String id) {
        if (id != null) {
            for (AppTheme t : APP_THEMES) {
                if (t.id.equals(id)) return t;
            }
            switch (id) {
                case "tokenite":
                    return appThemeById("ink");
                case "midnight":
                    return appThemeById("rose-pine");
                case "slate":
                    return appThemeById("rose-pine-moon");
                case "paper":
                    return appThemeById("rose-pine-dawn");
                default:
                    break;
            }
        }
        return APP_THEMES[0];
    }

    /** Resolves by app theme id, embedded code id, or legacy code-style names. */
    static CodeStyle codeStyleById(String id) {
        if (id != null) {
            for (AppTheme t : APP_THEMES) {
                if (t.id.equals(id) || t.code.id.equals(id)) return t.code;
            }
            switch (id) {
                case "github":
                case "solarized":
                case "paper":
                    return appThemeById("rose-pine-dawn").code;
                case "nord":
                case "slate":
                    return appThemeById("rose-pine-moon").code;
                case "monokai":
                case "dracula":
                case "midnight":
                    return appThemeById("rose-pine").code;
                case "tokenite":
                    return appThemeById("ink").code;
                case "material":
                default:
                    break;
            }
        }
        return APP_THEMES[0].code;
    }

    private ThemeConfig() {}
}
