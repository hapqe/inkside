package me.hapke.inkside;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.webkit.WebView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements ChatJsBridge.Host {
    // ---- Feature areas, each in its own class. Declared first: other fields
    // ---- capture them in method references and lambdas.
    final PenTools penTools = new PenTools(this);
    final TextTools textTools = new TextTools(this);
    final ChatView chatView = new ChatView(this);
    final Conversations conversations = new Conversations(this);
    final OverflowMenu overflowMenu = new OverflowMenu(this);
    final SettingsPanel settingsPanel = new SettingsPanel(this);
    final PageStyleMenu pageStyle = new PageStyleMenu(this);
    final ZenMode zen = new ZenMode(this);
    final Presentation slideshow = new Presentation(this);
    final Favorites favorites = new Favorites(this);
    final ColorPanels colorPanels = new ColorPanels(this);
    final BridgeStatus bridgeStatus = new BridgeStatus(this);
    final CanvasDrops canvasDrops = new CanvasDrops(this);
    final CanvasAgent canvasAgent = new CanvasAgent(this);
    final PdfExport pdfExport = new PdfExport(this);
    final FileTransfers transfers = new FileTransfers(this);
    final Documents documents = new Documents(this);
    final PageOrganizerDialog pageOrganizer = new PageOrganizerDialog(this);
    final PdfSearchDialog pdfSearch = new PdfSearchDialog(this);
    final CodeEditor codeEditor = new CodeEditor(this);
    final ExplorerPanel explorer = new ExplorerPanel(this);
    final Projects projects = new Projects(this);
    final ArtifactOverlays artifacts = new ArtifactOverlays(this);
    final Dictation dictation = new Dictation(this);
    final InstantChat instantChat = new InstantChat(this);
    final UndoScrubber undoScrubber = new UndoScrubber(this);
    final CanvasPaste canvasPaste = new CanvasPaste(this);
    final VizImages vizImages = new VizImages(this);
    final SessionPersistence persistence = new SessionPersistence(this);
    final RefreshRate refreshRate = new RefreshRate(this);
    final Computers computers = new Computers(this);

    static final String TAG = "Inkside";
    static final int REQ_PICK_ATTACHMENT = 1001;
    /** Role the files being picked for the chat get: "", "reference" or "goal". */
    String pendingAttachRole = "";
    static final String DROP_TEXT_LABEL = "codingcanvas-text";
    /** The connected computer's host (agent, scripts, voice); talks to nothing on the tablet's own workspace. */
    BridgeClient bridge;
    /** Where this run's documents live: {@link #localWorkspace} or {@link #bridge}. */
    Workspace workspace;
    LocalWorkspace localWorkspace;
    /** The document last open in each project (project path → document path), kept across restarts. */
    final java.util.Map<String, String> lastDocByProject = new java.util.HashMap<>();
    /** One JSON file per chat, mirrored to the linked computer so every device has the same chats. */
    java.io.File chatsDir;
    /** Keeps the linked computer's copy of the workspace in step; null without a linked, switched-on computer. */
    RemoteSync remoteSync;
    /** Which workspace this run shows (see {@link Profiles}); switching restarts the activity. */
    String profile;
    /** The computer behind {@link #profile}, or null (the tablet, or state from before connecting). */
    PairedHosts.Host pairedHost;
    CodeCanvasView canvas;
    LatexRenderer latexRenderer;
    WebView chatWeb;
    final StringBuilder chatPlain = new StringBuilder();
    boolean chatWebReady = false;
    EditText chatInput;
    ImageView chatAttachButton;
    /** Dictation: tap to record, tap again to transcribe on the Mac into the chat input. */
    ImageView chatMicButton;
    /** Shown only while listening: throws the recording away untranscribed. */
    ImageView chatMicDiscardButton;
    /** Kept as a field so theme changes re-tint it like its neighbours. */
    private ImageView cutSelButton;
    /** Spinner shown in the mic's place while a recording is being transcribed. */
    android.widget.ProgressBar chatMicSpinner;
    /** Mic + spinner in the text style bar: dictation into a text box. */
    ImageView inlineMicButton;
    android.widget.ProgressBar inlineMicSpinner;
    android.media.MediaRecorder voiceRecorder;
    boolean voiceTranscribing = false;
    /** Live voice bars in the composer while dictating (same view as the instant chat). */
    VoiceWaveView chatVoiceWave;
    /** Send was pressed mid-dictation: send once the transcript is in. */
    boolean sendAfterDictation;
    static final int REQ_RECORD_AUDIO = 4107;
    LinearLayout attachRow;
    HorizontalScrollView attachScroll;
    String openFile = null;
    String workspaceDir = ".";
    /** Active course/project (workspace-relative). Null/empty = All Projects shown. */
    String activeProjectPath;
    /** Last project entered — used when opening a shared PDF from All Projects. */
    String lastProjectPath;
    AllProjectsView allProjectsView;
    ImageView projectsBackButton;
    static final String PREFS_NAME = "codingcanvas";
    final List<PendingAttachment> pendingAttachments = new ArrayList<>();
    ImageView sendButton;
    ImageView stopButton;
    View chatComposerShell;
    LinearLayout chatTabsRow;
    LinearLayout chatRunBanner;
    TextView chatRunBannerLabel;
    ProgressBar chatRunBannerSpinner;
    final List<ChatSession> chats = new ArrayList<>();
    String activeChatId;
    /** Providers the Mac's `claude-provider` switches between, and how to show them. */
    static final String[] PROVIDER_NAMES = {"claude", "deepseek"};
    static final String[] PROVIDER_LABELS = {"Claude", "DeepSeek"};

    /**
     * PDFs this app created. A blank PDF made from the library is written straight to
     * disk and opened later like any other file, so without this it came back looking
     * imported — and page styling, which is only offered on the app's own blank pages,
     * was refused on a document the user had just created.
     */
    final java.util.HashSet<String> appCreatedDocs = new java.util.HashSet<>();

    /** Documents opened in this workspace, most recent first. */
    final java.util.ArrayList<String> recentDocs = new java.util.ArrayList<>();
    /** Document the saved session had open; startup reopens its project over the library. */
    String restoredDocumentPath = null;
    static final int RECENT_DOCS_MAX = 8;
    ImageView quickSwitchButton;
    /** Left-hand pill: leaving the document (library) and moving between documents. */
    LinearLayout navPill;
    private GradientDrawable navPillBg;

    AppStateStore stateStore;
    /** Per-PDF canvas slices (strokes, pageCount, blankPrefix, …), keyed by documentPath. */
    /** Every document's saved ink and pages, one file each; see DocumentStateStore. */
    DocumentStateStore docStore;
    /** Undo/redo history per document, so undo survives closing it. */
    UndoHistoryStore undoHistory;
    boolean keepUndoHistory = true;
    /**
     * Chat, instant chat and dictation (Settings → AI features). Read once per
     * run: switching it restarts the activity, so every screen is built one way.
     */
    boolean aiEnabled = true;
    static final String PREF_AI_FEATURES = "aiFeatures";
    /** Learning Mode (Settings → AI): the tutor adapts instead of handing out solutions. */
    static final String PREF_LEARNING_MODE = "learningMode";
    boolean learningMode = false;
    /** Settings → AI: whether the chat agent may take screenshots of your pages. */
    static final String PREF_AGENT_SEES_PAGES = "agentSeesPages";
    boolean agentSeesPages = true;
    /** On-device handwriting recognition, feeding search. Off until turned on. */
    HandwritingIndex handwriting;
    final android.os.Handler saveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable saveRunnable = persistence::saveSessionNow;
    /** True after UI mutations until the next successful {@link SessionPersistence#saveSessionNow}. */
    boolean sessionDirty = false;
    boolean restoring = false;
    /** Serializes session writes so two saves cannot interleave on the same file. */
    final java.util.concurrent.ExecutorService saveExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cc-save");
                t.setDaemon(true);
                return t;
            });
    final List<View> colorButtons = new ArrayList<>();
    ImageView pencilButton;
    ImageView eraserButton;
    ImageView lassoButton;
    private ImageView addToChatButton;
    FrameLayout explorerPanel;
    FrameLayout.LayoutParams explorerPanelLp;
    FolderExplorerView folderExplorer;
    boolean explorerCollapsed = true;
    boolean explorerOnLeft = true;
    ValueAnimator explorerSlideAnim;
    View explorerEdgeDrag;
    FrameLayout.LayoutParams explorerEdgeDragLp;
    VelocityTracker explorerVelocityTracker;
    private static final int MAX_SCRIPT_META_ENTRIES = 64;
    /**
     * Read from the WebView's JavaBridge thread as well as the main thread, so it must be
     * synchronized. Insertion-ordered rather than access-ordered on purpose: access order
     * makes {@code get()} a structural mutation, which is far worse to race on.
     */
    final java.util.Map<String, String> scriptMetaCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(16, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, String> eldest) {
                            return size() > MAX_SCRIPT_META_ENTRIES;
                        }
                    });
    ImageView undoButton;
    ImageView redoButton;
    /** History scrubber shown while sliding across undo/redo; lives over the canvas. */
    UndoScrubView undoScrub;
    FrameLayout undoScrubHost;
    ImageView openFileButton;
    ImageView settingsButton;
    ImageView chipInk;
    ImageView chipImg;
    private ImageView deleteSelButton;
    private ImageView copySelButton;
    private ImageView editSelButton;
    View pencilOptions;
    View targetOptions;
    private LinearLayout toolRowMain;
    LinearLayout toolRowOptions;
    boolean toolOptionsExpanded;
    LinearLayout selectionActions;
    private GradientDrawable selectionActionsBg;
    private FrameLayout.LayoutParams selectionActionsLp;
    FrameLayout editorPanel;
    FrameLayout.LayoutParams editorPanelLp;
    ImageView editorToggleButton;
    CodeEditorView scriptEditorInput;
    String scriptEditorPath;
    /** When set, the editor panel shows a rendered .viz (WebView), never source. */
    String editorVizArtifact;
    boolean editorCollapsed = true;
    boolean editorDirty = false;
    /** View-only: browse/select code without editing. */
    boolean editorViewOnly = false;
    /** Unsaved buffer carried over from the session blob, applied once the file loads. */
    String pendingEditorText;
    ValueAnimator editorSlideAnim;
    int editorPanelWidthPx = -1;
    View editorResizeHandle;
    View editorResizeGrip;
    boolean editorResizing = false;
    int explorerPanelWidthPx = -1;
    View explorerResizeHandle;
    View explorerResizeGrip;
    boolean explorerResizing = false;
    InlineTextEditor inlineEditor;
    TextView fontCycleChip;
    FrameLayout chatPanel;
    private LinearLayout chatDragHeader;
    private TextView chatDragHeaderTitle;
    ImageView chatMoreFab;
    ImageView chatCanvasFab;
    ImageView chatNewFab;
    LinearLayout chatTabsDrawer;
    boolean chatResizing = false;
    /** 0 = unknown, 1 = no h-scroll under finger, 2 = horizontally scrollable under finger. */
    volatile int chatWebTouchScrollXState = 0;
    boolean chatCollapsed = true;
    int chatPanelWidthPx = -1;
    int selectedColorIndex = 0;
    boolean eraserSelected = false;
    boolean lassoSelected = false;
    boolean textSelected = false;
    // Compact spacing scale (dp): 2 / 4 / 6 / 8 / 12
    static final int SPACE_XS = 2;
    static final int SPACE_SM = 4;
    static final int SPACE_MD = 6;
    static final int SPACE_LG = 8;
    static final int SPACE_XL = 12;
    static final int ICON_SIZE = 36;
    /**
     * Half the collapsed row height, so the bar keeps the same corner curvature
     * when a second row opens. A radius of 999 turned the expanded bar into a
     * stadium with enormous round ends instead of the pill grown downwards.
     */
    static final int TOOL_PILL_RADIUS = (ICON_SIZE + 2 * 2) / 2;
    static final int ICON_PAD = 7;
    private static final int FAB_SIZE = 52;
    static final int SWATCH_SIZE = 20;
    static final int CHAT_PANEL_W = 300;
    static final int CHAT_PANEL_MIN_W = 220;
    static final int EXPLORER_PANEL_W = 220;
    static final int EXPLORER_PANEL_MIN_W = 160;
    static final int EDITOR_PANEL_W = 420;
    static final int EDITOR_PANEL_MIN_W = 280;
    /** Cap on the unsaved buffer written into the session blob. */
    static final int MAX_EDITOR_BUFFER_CHARS = 400_000;
    static final int CHAT_RESIZE_HANDLE_W = 24;
    static final int CHAT_RESIZE_HANDLE_H = 72;
    static final int CHAT_RESIZE_PILL_W = 4;
    static final int CHAT_RESIZE_PILL_H = 28;
    /** Inset around chat content; bottom and side must match so the composer sits evenly. */
    /** Gap kept between the chat composer and the gesture pill while typing (dp). */
    private static final int CHAT_IME_PILL_CLEARANCE = 24;
    static final int CHAT_CONTENT_INSET = 6;
    /** Composer pill radius (~half of min height) + inset → concentric with the shell. */
    static final int CHAT_COMPOSER_MIN_H = 48;
    /**
     * Half the empty composer height — same idea as {@link #TOOL_PILL_RADIUS}. A radius
     * of 999 made multi-line drafts look like a circle/stadium instead of a rounded rect.
     */
    static final int CHAT_COMPOSER_RADIUS = CHAT_COMPOSER_MIN_H / 2;
    static final int CHAT_BG_CORNER = CHAT_COMPOSER_RADIUS + CHAT_CONTENT_INSET;
    private static final float SIDE_PANEL_MAX_FRAC = 0.55f;
    /** Canvas that always stays uncovered between the panels on either side, in dp. */
    private static final int MIN_FREE_CANVAS_W = 120;
    static final int[] INK_COLORS = {
            0xFF4C8DFF, 0xFF3DDB8A, 0xFFF5D76E, 0xFFFF6B6B, 0xFFC792EA
    };
    /**
     * The effect brushes' starting colours: bright, saturated hues, so glows and
     * sparkles read as light against the page.
     */
    private static final int[] GLOW_COLORS = {
            0xFF22D3EE, 0xFFF472B6, 0xFFA3E635, 0xFFFBBF24, 0xFFA78BFA
    };

    final PenTools.PenState[] pens = new PenTools.PenState[CodeCanvasView.BRUSH_COUNT];

    {
        for (int b = 0; b < pens.length; b++) {
            PenTools.PenState p = new PenTools.PenState();
            System.arraycopy(b == CodeCanvasView.BRUSH_INK ? INK_COLORS : GLOW_COLORS,
                    0, p.slots, 0, 5);
            switch (b) {
                case CodeCanvasView.BRUSH_HIGHLIGHTER: p.thickness = 50; break;
                case CodeCanvasView.BRUSH_CALLIGRAPHY: p.thickness = 45; break;
                case CodeCanvasView.BRUSH_SPRAY: p.thickness = 45; break;
                default: break;
            }
            p.sizes[0] = Math.max(0, p.thickness - 20);
            p.sizes[1] = p.thickness;
            p.sizes[2] = Math.min(100, p.thickness + 30);
            pens[b] = p;
        }
    }

    /**
     * Favourite colours, shared by all pens: a pen's five swatches are picked from
     * these. Added to from the colour panel's picker; entries can be removed there.
     */
    final List<Integer> favoriteColors = new ArrayList<>();

    {
        for (int c : INK_COLORS) favoriteColors.add(c);
        for (int c : GLOW_COLORS) favoriteColors.add(c);
        for (int c : new int[]{0xFFFFFFFF, 0xFF1F1F1F, 0xFFFB923C, 0xFF14B8A6}) favoriteColors.add(c);
    }

    /** Brush for new strokes; each brush is a pen with its own {@link PenState}. */
    int brush = CodeCanvasView.BRUSH_INK;

    // App chrome colors (mutable via options)
    int M3_SURFACE = 0xFF13131A;
    int M3_SURFACE_CONTAINER = 0xFF1C1B22;
    int M3_SURFACE_CONTAINER_HIGH = 0xE626252E;
    int M3_SURFACE_CONTAINER_HIGHEST = 0xFF31303A;
    int M3_ON_SURFACE = 0xFFE6E1E9;
    int M3_ON_SURFACE_VARIANT = 0xFFCAC4D0;
    int M3_PRIMARY = 0xFFBAC3FF;
    int M3_PRIMARY_CONTAINER = 0xFF3F51B5;
    int M3_ON_PRIMARY_CONTAINER = 0xFFE8EAF6;
    int M3_SECONDARY_CONTAINER = 0xFF47464F;
    int M3_OUTLINE_VARIANT = 0xFF49454F;

    FrameLayout rootLayout;
    View centerPane;
    FrameLayout.LayoutParams centerPaneLp;
    /** Translates the canvas window; insets/size apply when it ends. */
    private ValueAnimator centerPaneInsetAnim;
    /** While animating insets, tool-pill uses these instead of live panel widths. */

    private int insetAnimOldLeft;
    private int insetAnimOldRight;
    private int insetAnimNewLeft;
    private int insetAnimNewRight;
    private float insetAnimFraction;
    LinearLayout chatShell;
    LinearLayout chatContentCol;
    View chatResizeHandle;
    ValueAnimator chatSlideAnim;
    VelocityTracker chatVelocityTracker;
    private View chatHeaderRule;
    View optionsOverlay;
    LinearLayout optionsCard;
    /** 0 = idle, 1 = listening for primary map, 2 = listening for secondary map. */
    int penMapListening;
    /** Tracks Ctrl+4 / Ctrl+5 holds so UP is recognized even if Ctrl already released. */
    private boolean penKeyAHeld;
    private boolean penKeyBHeld;
    static final int TOOL_SHADOW_PAD = 14;
    static final int TOOL_SHADOW_TOP = 6;
    HorizontalScrollView toolScroll;
    private FrameLayout.LayoutParams toolScrollLp;
    private LinearLayout toolPill;
    /** What toolScroll scrolls: the tool pill, and the nav pill too when the two do not fit side by side. */
    private LinearLayout scrollRow;
    private FrameLayout toolFrame;
    private FrameLayout.LayoutParams navFrameLp;
    private GradientDrawable toolPillBg;
    String appThemeId = "matcha";
    String codeStyleId = "material";
    boolean chatOnLeft = true;
    ImageView textToolButton;
    View textStyleFloatingBar;
    private GradientDrawable textStyleBarBg;
    LinearLayout textStyleInner;
    SeekBar thicknessSeekBar;
    SeekBar stabilizationSeekBar;
    SeekBar pressureSeekBar;
    ImageView penSettingsButton;
    private final List<View> toolDividers = new ArrayList<>();
    TextView chatTabsDrawerTitle;
    private FrameLayout.LayoutParams textStyleBarLp;
    private SeekBar textSizeBar;
    FrameLayout.LayoutParams chatPanelLp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            // Apps targeting Android 16 no longer get onBackPressed(): without this, back closed the app.
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (!handleBack()) moveTaskToBack(true);
                    });
        }
        // First: a crash anywhere after this point is kept and sent on the next launch.
        CrashReporter.install(this);

        aiEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AI_FEATURES, true);
        studyLog = new StudyLog(this);
        // The workspace this run shows, and the computer behind it (if any). Applied
        // before anything can read a document or talk to a host.
        profile = Profiles.active(this);
        // The documents are always the tablet's own; a linked computer (when switched on)
        // adds the agent, scripts and voice and holds a synced copy.
        pairedHost = PairedHosts.enabled(this) ? PairedHosts.remote(this) : null;
        if (pairedHost != null) {
            bridge = new BridgeClient(pairedHost.url());
            bridge.setToken(pairedHost.token);
        } else {
            bridge = new BridgeClient("");
        }
        localWorkspace = new LocalWorkspace(Profiles.localWorkspaceRoot(this), new LocalPdf(this));
        workspace = localWorkspace;
        localWorkspace.seedIfEmpty();
        learningMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_LEARNING_MODE, false);
        bridge.setLearningMode(learningMode);
        agentSeesPages = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AGENT_SEES_PAGES, true);
        bridge.setAllowPageView(agentSeesPages);
        java.io.File profileDir = Profiles.filesDir(this, profile);
        chatsDir = new java.io.File(profileDir, "chats");
        stateStore = new AppStateStore(profileDir);
        docStore = new DocumentStateStore(profileDir);
        undoHistory = new UndoHistoryStore(profileDir);
        handwriting = new HandwritingIndex(profileDir);
        handwriting.setOnReady(documents::scheduleInkIndex);
        if (pairedHost != null) {
            remoteSync = new RemoteSync(localWorkspace, bridge, profileDir, docStore, chatsDir);
            remoteSync.setLive(PairedHosts.liveSync(this));
            remoteSync.setListener(new RemoteSync.Listener() {
                @Override
                public void onSyncStatus(String text, boolean busy, boolean error) {
                    computers.onSyncStatus(text, busy, error);
                }

                @Override
                public void onSyncProgress(int done, int total, boolean busy) {
                    computers.onSyncProgress(done, total, busy);
                }

                @Override
                public void onStateFilesChanged(java.util.List<String> pulled, java.util.List<String> deleted) {
                    if (isDead() || docStore == null) return;
                    java.util.List<String> docPulled = new java.util.ArrayList<>();
                    java.util.List<String> docGone = new java.util.ArrayList<>();
                    java.util.List<String> chatPulled = new java.util.ArrayList<>();
                    java.util.List<String> chatGone = new java.util.ArrayList<>();
                    for (String p : pulled) {
                        if (p.startsWith(RemoteSync.CHAT_PREFIX)) chatPulled.add(p.substring(RemoteSync.CHAT_PREFIX.length()));
                        else if (p.startsWith(RemoteSync.STATE_PREFIX)) docPulled.add(p.substring(RemoteSync.STATE_PREFIX.length()));
                    }
                    for (String p : deleted) {
                        if (p.startsWith(RemoteSync.CHAT_PREFIX)) chatGone.add(p.substring(RemoteSync.CHAT_PREFIX.length()));
                        else if (p.startsWith(RemoteSync.STATE_PREFIX)) docGone.add(p.substring(RemoteSync.STATE_PREFIX.length()));
                    }
                    if (!docPulled.isEmpty() || !docGone.isEmpty()) {
                        java.util.List<String> changed = docStore.reloadFromFiles(docPulled, docGone);
                        String open = canvas != null ? canvas.getDocumentPath() : null;
                        if (open != null && changed.contains(open)) documents.reloadOpenInkFromStore();
                    }
                    if (!chatPulled.isEmpty() || !chatGone.isEmpty()) conversations.adoptChatFiles(chatPulled, chatGone);
                }

                @Override
                public void onLocalFilesChanged() {
                    if (isDead()) return;
                    refreshFileViews();
                    documents.reloadOpenPdfIfChanged();
                    artifacts.reloadArtifactOverlays();
                }
            });
            remoteSync.start();
        }
        // Crashes saved on an earlier run go to the connected computer (host/logs/crashes).
        if (computers.hasHost()) {
            CrashReporter.uploadPending(this, bridge, sent -> {
                if (sent > 0) runOnUiThread(() -> {
                    if (!isDead()) statusToast("Sent " + sent + " crash report" + (sent == 1 ? "" : "s")
                            + " to " + computers.workspaceName());
                });
            });
        }

        // Soft keyboard allowed for the message field; shortcuts still force-hide it.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        hideSystemBars();

        rootLayout = new FrameLayout(this) {
            @Override
            public void removeView(View view) {
                // Windows that popped in drop away again instead of vanishing: the
                // view leaves the hierarchy now (state checks see it gone at once)
                // but is still drawn until its exit animation ends.
                if (view != null && openedWindows.remove(view) && indexOfChild(view) >= 0
                        && getWindowToken() != null) {
                    startViewTransition(view);
                    super.removeView(view);
                    animateWindowClose(this, view);
                    return;
                }
                super.removeView(view);
            }
        };
        rootLayout.setBackgroundColor(M3_SURFACE);
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);
        setContentView(rootLayout);
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(this::scheduleHoverSweep);
        // Every window that opens over the app (settings, menus, pickers, dialogs built
        // in-app) fades in with its card rising into place.
        rootLayout.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                animateWindowOpen(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            onImeInsetsChanged(insets);
            return insets;
        });
        // Insets can land before the resize for the keyboard has been laid out;
        // re-measure once the root has its new size.
        rootLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) v.post(() -> onImeInsetsChanged(v.getRootWindowInsets()));
        });
        latexRenderer = new LatexRenderer(this);
        latexRenderer.init(rootLayout);
        refreshRate.startRefreshRateKeepalive();
        getWindow().getDecorView().post(refreshRate::requestHighRefreshRate);

        centerPaneLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centerPane = buildCenter();
        chatPanelLp = new FrameLayout.LayoutParams(
                dp(CHAT_PANEL_W), ViewGroup.LayoutParams.MATCH_PARENT);
        chatPanelLp.gravity = Gravity.END;
        chatPanel = chatView.buildChat();
        chatPanel.setElevation(0f);
        chatView.refreshChatPanelBackground();
        chatView.applyChatSide(true);
        chatView.applyChatCollapsed(true);

        penTools.selectPencil(0);
        // Adding a favorite anywhere (tool, ink, file, project) opens the Quick
        // favorites menu so the new one can be placed or removed right away.
        FavoritesStore.get(this).setOnAdded(id -> {
            if (isDead()) return;
            rootLayout.post(() -> {
                if (!isDead()) favorites.showFavoritesMenu(id);
            });
        });
        projects.ensureAllProjectsView();
        persistence.restoreSession();
        if (activeProjectPath != null && !activeProjectPath.isEmpty()) {
            lastProjectPath = activeProjectPath;
        }
        activeProjectPath = null;
        // Reopen where the last session left off: restoreSession already rebinds the
        // last document to the canvas, so entering its project is all that is left.
        // With no document (or no project to hold it) start on the library home.
        if (restoredDocumentPath != null && lastProjectPath != null && !lastProjectPath.isEmpty()) {
            projects.enterProject(lastProjectPath, Projects.projectDisplayName(lastProjectPath), null);
        } else {
            projects.showAllProjects();
        }
        if (computers.hasHost()) canvasAgent.startCanvasEvents();
        // Set up before connecting by address existed: its documents are on a
        // computer this tablet has not been told about yet.
    }

    /**
     * Learning Mode on/off, for every chat at once: each message carries the switch,
     * and the host stores it as well. The chat's hint shows when it is on.
     */
    void setLearningMode(boolean on) {
        learningMode = on;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_LEARNING_MODE, on).apply();
        if (bridge != null) {
            bridge.setLearningMode(on);
            if (computers.hasHost()) bridge.postLearningMode(on, null);
        }
        if (chatInput != null && chatInput.getText().length() == 0 && voiceRecorderIdle()) {
            chatInput.setHint(chatIdleHint());
        }
        refreshLearningBadge();
    }

    /**
     * Page viewing on/off. Each message tells the host (which then leaves the agent's
     * page tool out), and the app itself refuses page captures while it is off.
     */
    void setAgentSeesPages(boolean on) {
        agentSeesPages = on;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AGENT_SEES_PAGES, on).apply();
        if (bridge != null) bridge.setAllowPageView(on);
    }

    /** Placeholder of the message box when nothing is recording or transcribing. */
    String chatIdleHint() {
        return "Message…";
    }

    /**
     * Learning Mode is shown as a small bolt — the icon Settings uses for it — at the
     * start of the message box, not as text in the placeholder.
     */
    void refreshLearningBadge() {
        if (chatInput == null) return;
        if (!learningMode) {
            chatInput.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        android.graphics.drawable.Drawable bolt = getDrawable(R.drawable.ic_bolt).mutate();
        bolt.setTint(M3_PRIMARY);
        bolt.setBounds(0, 0, dp(16), dp(16));
        chatInput.setCompoundDrawablesRelative(bolt, null, null, null);
        chatInput.setCompoundDrawablePadding(dp(SPACE_SM));
    }

    private boolean voiceRecorderIdle() {
        CharSequence h = chatInput != null ? chatInput.getHint() : null;
        return h == null || h.toString().startsWith("Message");
    }

    /** Turns the AI features on or off; restarts so every screen is rebuilt that way. */
    void setAiEnabled(boolean on) {
        if (on == aiEnabled) return;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AI_FEATURES, on).commit();
        if (!restoring) persistence.saveSessionNow(true);
        recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        refreshRate.startRefreshRateKeepalive();
        conversations.syncChatRunState();
        conversations.resumeRunningSessions();
        canvasAgent.startCanvasEvents();
        if (canvas != null) canvas.cancelFavoritesRadial();
        documents.reloadOpenPdfIfChanged();
        if (computers.hasHost()) bridgeStatus.startBridgeHealthWatch();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            refreshRate.requestHighRefreshRate();
            refreshRate.startRefreshRateKeepalive();
        }
    }

    @Override
    protected void onPause() {
        refreshRate.stopRefreshRateKeepalive();
        bridgeStatus.stopBridgeHealthWatch();
        // Time spent outside the app does not count.
        if (stopwatch != null) stopwatch.pauseForLeaving();
        // Never keep the mic open in the background.
        dictation.stopVoiceRecording(false);
        persistence.saveSessionNow(true);
        super.onPause();
    }

    @Override
    protected void onStop() {
        persistence.saveSessionNow(true);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Flush any pending debounced save before tearing down callbacks.
        saveHandler.removeCallbacks(saveRunnable);
        FavoritesStore.get(this).setOnAdded(null);
        slideshow.stopPresentation(null);
        if (miniChat != null) {
            miniChat.destroy();
            miniChat = null;
        }
        if (!restoring) persistence.saveSessionNow(true);
        refreshRate.stopRefreshRateKeepalive();
        // Drop every pending post: chatBusyWatchdog, restoreWatchdog and
        // the ad-hoc delayed posts all capture this Activity.
        saveHandler.removeCallbacksAndMessages(null);
        if (inlineEditor != null) {
            inlineEditor.dismiss(false);
            inlineEditor = null;
        }
        if (chatVelocityTracker != null) {
            chatVelocityTracker.recycle();
            chatVelocityTracker = null;
        }
        if (explorerVelocityTracker != null) {
            explorerVelocityTracker.recycle();
            explorerVelocityTracker = null;
        }
        if (chatWeb != null) {
            // The JS interface holds a strong ref to this Activity.
            chatWeb.removeJavascriptInterface("AndroidBridge");
            ViewGroup parent = (ViewGroup) chatWeb.getParent();
            if (parent != null) parent.removeView(chatWeb);
            chatWeb.destroy();
            chatWeb = null;
            chatWebReady = false;
        }
        if (latexRenderer != null) {
            latexRenderer.destroy();
            latexRenderer = null;
        }
        if (bridge != null) bridge.shutdown();
        if (remoteSync != null) remoteSync.stop();
        if (localWorkspace != null) localWorkspace.shutdown();
        saveExecutor.shutdown();
        super.onDestroy();
    }

    /** Sticky immersive: hide status + nav bars; re-hide if the system shows them. */
    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.getClass()
                        .getMethod("setDecorFitsSystemWindows", boolean.class)
                        .invoke(window, false);
                Object controller = window.getClass().getMethod("getInsetsController").invoke(window);
                if (controller != null) {
                    Class<?> typeClass = Class.forName("android.view.WindowInsets$Type");
                    int bars = (Integer) typeClass.getMethod("statusBars").invoke(null)
                            | (Integer) typeClass.getMethod("navigationBars").invoke(null);
                    controller.getClass().getMethod("hide", int.class).invoke(controller, bars);
                    // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE == 2
                    controller.getClass()
                            .getMethod("setSystemBarsBehavior", int.class)
                            .invoke(controller, 2);
                }
            } catch (Throwable ignored) {
            }
        }
        final int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        decor.setSystemUiVisibility(flags);
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                    || (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                decor.post(this::hideSystemBars);
            }
        });
    }

    /** Extra bottom inset currently applied to the chat column for the keyboard. */
    private int chatImeLiftPx = 0;

    /**
     * With the keyboard up, the system gesture pill sits over the bottom of the
     * composer, so only its upper half took taps. Lift the composer clear of the
     * keyboard and the pill by padding the chat column — the message list above
     * gets shorter instead of the whole panel sliding up and losing its top.
     */
    private void onImeInsetsChanged(WindowInsets insets) {
        if (insets == null || rootLayout == null) return;
        int lift = 0;
        if (insets.isVisible(WindowInsets.Type.ime())) {
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            // Whether or not the window was resized for the keyboard, find how much of
            // the panel still sits under it (0 when the window already shrank).
            // Window metrics are the activity's full frame (the IME inset is measured
            // against it) even when adjustResize has shrunk the view hierarchy.
            int windowBottom = getWindowManager().getCurrentWindowMetrics().getBounds().bottom;
            int[] loc = new int[2];
            rootLayout.getLocationOnScreen(loc);
            int rootBottom = loc[1] + rootLayout.getHeight();
            int imeTop = windowBottom - imeBottom;
            int overlap = Math.max(0, rootBottom - imeTop);
            int pill = Math.max(
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom);
            lift = overlap + Math.max(dp(CHAT_IME_PILL_CLEARANCE), pill);
        }
        applyChatImeLift(lift);
    }

    private void applyChatImeLift(int lift) {
        if (lift == chatImeLiftPx || chatContentCol == null) return;
        chatImeLiftPx = lift;
        chatContentCol.setPadding(chatContentCol.getPaddingLeft(), chatContentCol.getPaddingTop(),
                chatContentCol.getPaddingRight(), dp(CHAT_CONTENT_INSET) + lift);
    }

    int statusBarHeight() {
        // Bars are hidden in immersive mode — keep a tiny inset only.
        return dp(SPACE_SM);
    }

    private View buildCenter() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(M3_SURFACE);

        canvas = new CodeCanvasView(this);
        canvas.setBaseThicknessPx(PenTools.thicknessForProgress(35));
        canvas.setListener(new CodeCanvasView.Listener() {
            @Override
            public void onContentChanged() {
                persistence.scheduleSave();
                documents.scheduleInkIndex();
            }

            @Override
            public void onEmptyCreateRequested() {
                documents.promptCreatePdfDocument();
            }

            @Override
            public void onCanvasLongPress(float worldX, float worldY, float screenX, float screenY) {
                canvasPaste.showCanvasLongPressMenu(worldX, worldY, screenX, screenY);
            }

            @Override
            public void onFavoritesRadialOpenRequested(float x, float y) {
                if (canvas == null) return;
                if (favorites.buildRadialFavoriteItems().isEmpty()) return;
                canvas.applyFavoritesRadialColors(
                        M3_SURFACE, M3_SURFACE_CONTAINER_HIGHEST, M3_PRIMARY,
                        M3_PRIMARY_CONTAINER, M3_ON_PRIMARY_CONTAINER,
                        M3_ON_SURFACE, M3_ON_SURFACE_VARIANT, M3_OUTLINE_VARIANT);
                canvas.openFavoritesRadial(favorites.buildRadialFavoriteItems(), x, y);
            }

            @Override
            public void onFavoritesRadialPicked(String favoriteId) {
                if (favoriteId != null) favorites.activateFavorite(favoriteId);
            }

            @Override
            public void onUndoScrubStart(float x, float y) {
                // Above the pen, where the writing hand doesn't cover it.
                undoScrubber.beginUndoScrub(canvas, x, y, false, dp(20));
            }

            @Override
            public void onUndoScrubDrag(float dx) {
                undoScrubber.dragUndoScrub(dx);
            }

            @Override
            public void onUndoScrubEnd() {
                undoScrubber.endUndoScrub();
            }

            @Override
            public void onHistoryChanged(boolean canUndo, boolean canRedo) {
                undoScrubber.syncUndoRedoButtons();
            }

            @Override
            public void onFavoritesRadialRemoveRequested(String favoriteId) {
                if (favoriteId == null) return;
                FavoritesStore.get(MainActivity.this).setFavorite(favoriteId, false);
                if (canvas != null) {
                    canvas.refreshFavoritesRadial(favorites.buildRadialFavoriteItems());
                }
            }

            @Override
            public void onSelectionChanged(boolean hasSelection) {
                refreshSelectionActions(hasSelection);
            }

            @Override
            public void onLiveArtifactActivated(CanvasImage img) {
                artifacts.enterArtifact(img);
            }

            @Override
            public void onSelectionLayoutChanged() {
                if (pdfTextSelection != null) pdfTextSelection.invalidate();
                positionFloatingSelectionActions();
                if (inlineEditor != null) inlineEditor.reposition();
                artifacts.syncArtifactOverlays();
            }

            @Override
            public void onNavigationChanged(boolean navigating) {
                if (pdfTextSelection != null) pdfTextSelection.invalidate();
                // Hide floating chrome while pan/zoom; restore when the gesture ends.
                if (navigating) {
                    if (selectionActions != null) selectionActions.setVisibility(View.GONE);
                    if (textStyleFloatingBar != null) textStyleFloatingBar.setVisibility(View.GONE);
                } else {
                    refreshSelectionActions(canvas != null && canvas.hasActiveSelection());
                    // Where you are in a document is worth keeping. Panning and zooming
                    // changed no content, so nothing marked the session dirty and the
                    // camera was only ever saved by accident, alongside some other edit.
                    persistence.scheduleCameraSave();
                }
            }

            @Override
            public void onLassoRegionChanged(boolean hasRegion) {
                refreshSelectionActions(canvas.hasActiveSelection());
            }

            @Override
            public void onRequestAppendPage() {
                documents.appendDocumentPageFromOvershoot();
            }

            @Override
            public void onRequestPrependPage() {
                documents.prependDocumentPageFromOvershoot();
            }

            @Override
            public void onTextFieldEditDismissRequested() {
                textTools.commitInlineEdit();
            }

            @Override
            public void onTextFieldEditRequested(String id) {
                textTools.openTextFieldEditor(id);
            }

            @Override
            public void onTextFieldRectCreated() {
                penTools.restoreToolAfterText(false);
            }

            @Override
            public void onTextFieldNeedsLatexRender(String id) {
                textTools.requestLatexRenderForField(id);
            }
        });
        frame.addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        canvasDrops.setupCanvasDragDrop(canvas);

        // PDF text selection sits right over the canvas; it only keeps touches that land
        // on its handles or its Copy / Add to chat bar.
        pdfTextSelection = new PdfTextSelectionView(this, new PdfTextSelectionView.Host() {
            @Override
            public DocumentPages.TextSelection select(float wx0, float wy0, float wx1, float wy1) {
                return canvas != null ? canvas.selectPdfText(wx0, wy0, wx1, wy1) : null;
            }

            @Override
            public float[] toScreen(float wx, float wy) {
                return canvas.screenFromWorld(wx, wy);
            }

            @Override
            public float[] toWorld(float sx, float sy) {
                float[] w = canvas.worldFromScreen(sx, sy);
                return new float[]{w[0], w[1]};
            }

            @Override
            public boolean cameraMoving() {
                return canvas != null && canvas.isNavigating();
            }

            @Override
            public void copyText(String text) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PDF text", text));
            }

            @Override
            public void addTextToChat(String text) {
                conversations.addPdfQuoteToChat(text);
            }
        });
        applyPdfSelectionColors();
        pdfTextSelection.setChatEnabled(aiEnabled);
        frame.addView(pdfTextSelection, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating tool bar overlay.
        toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.setFillViewport(false);
        // Room around the pills for their shadows: a scroll view clips what it draws to its own
        // bounds, which cut the shadow off along the bottom and sides.
        toolScroll.setClipToPadding(false);
        toolScroll.setClipChildren(false);
        toolScroll.setPadding(dp(TOOL_SHADOW_PAD), dp(TOOL_SHADOW_TOP), dp(TOOL_SHADOW_PAD), dp(TOOL_SHADOW_PAD));
        toolScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout pill = new LinearLayout(this);
        toolPill = pill;
        // Two stacked rows: tools on top, the selected tool's own settings beneath,
        // shown only when expanded. They used to share one row, which grew the bar
        // sideways every time a tool had more than a couple of options.
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER_HORIZONTAL);
        pill.setPadding(dp(SPACE_SM), dp(SPACE_XS), dp(SPACE_MD), dp(SPACE_XS));
        toolPillBg = new GradientDrawable();
        toolPillBg.setCornerRadius(dp(TOOL_PILL_RADIUS));
        toolPillBg.setColor(M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
        pill.setBackground(toolPillBg);
        pill.setElevation(dp(4));
        // Don't clip the expand chevron / selected icon rings against the pill edge.
        pill.setClipToOutline(false);

        LinearLayout mainRow = new LinearLayout(this);
        toolRowMain = mainRow;
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        pill.addView(mainRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout optionRow = new LinearLayout(this);
        toolRowOptions = optionRow;
        optionRow.setOrientation(LinearLayout.HORIZONTAL);
        optionRow.setGravity(Gravity.CENTER_VERTICAL);
        optionRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optLp.topMargin = dp(SPACE_XS);
        pill.addView(optionRow, optLp);

        // Undo/redo lead the bar, then drawing tools, then folder + settings.
        undoButton = iconBtn(R.drawable.ic_undo, () -> {
            if (canvas != null) {
                canvas.undo();
                persistence.scheduleSave();
            }
        });
        favorites.bindFavoriteLongPress(undoButton, FavoritesStore.TOOL_UNDO);
        undoScrubber.bindUndoScrub(undoButton);
        mainRow.addView(undoButton, iconLp());

        // Getting out of the document and moving between documents are not drawing
        // tools, so they live in their own pill at the left edge rather than competing
        // for room in the middle of the tool bar.
        projectsBackButton = iconBtn(R.drawable.ic_chevron_left, projects::showAllProjects);
        projectsBackButton.setContentDescription("All Projects");
        quickSwitchButton = iconBtn(R.drawable.ic_menu, overflowMenu::showRecentDocsMenu);
        quickSwitchButton.setContentDescription("Recent documents");
        ImageView pagesButton = iconBtn(R.drawable.ic_pages, pageOrganizer::showPageOrganizer);
        pagesButton.setContentDescription("Pages: reorder, duplicate, insert, delete");
        ImageView searchButton = iconBtn(R.drawable.ic_search, pdfSearch::showPdfSearch);
        searchButton.setContentDescription("Search PDFs");

        navPill = new LinearLayout(this);
        navPill.setOrientation(LinearLayout.HORIZONTAL);
        navPill.setGravity(Gravity.CENTER_VERTICAL);
        navPill.setPadding(dp(SPACE_SM), dp(SPACE_XS), dp(SPACE_SM), dp(SPACE_XS));
        navPillBg = new GradientDrawable();
        navPillBg.setCornerRadius(dp(TOOL_PILL_RADIUS));
        navPillBg.setColor(M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
        navPill.setBackground(navPillBg);
        navPill.setElevation(dp(4));
        navPill.setClipToOutline(false);
        navPill.addView(projectsBackButton, iconLp());
        navPill.addView(quickSwitchButton, iconLp());
        navPill.addView(pagesButton, iconLp());
        navPill.addView(searchButton, iconLpLast());
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.TOP | Gravity.START;
        navLp.topMargin = statusBarHeight() + dp(SPACE_MD);
        frame.addView(navPill, navLp);
        toolFrame = frame;
        navFrameLp = navLp;
        projects.syncProjectsBackButton();
        overflowMenu.syncQuickSwitchButton();

        redoButton = iconBtn(R.drawable.ic_redo, () -> {
            if (canvas != null) {
                canvas.redo();
                persistence.scheduleSave();
            }
        });
        favorites.bindFavoriteLongPress(redoButton, FavoritesStore.TOOL_REDO);
        undoScrubber.bindUndoScrub(redoButton);
        undoScrubber.syncUndoRedoButtons();
        mainRow.addView(redoButton, iconLp());
        mainRow.addView(toolDivider());

        pencilButton = iconBtn(R.drawable.ic_pencil, () -> penTools.selectPencil(selectedColorIndex));
        favorites.bindFavoriteLongPress(pencilButton, FavoritesStore.TOOL_PENCIL);
        eraserButton = iconBtn(R.drawable.ic_eraser, penTools::selectEraser);
        favorites.bindFavoriteLongPress(eraserButton, FavoritesStore.TOOL_ERASER);
        lassoButton = iconBtn(R.drawable.ic_lasso, penTools::selectLasso);
        favorites.bindFavoriteLongPress(lassoButton, FavoritesStore.TOOL_LASSO);
        textToolButton = iconBtn(R.drawable.ic_text, penTools::selectText);
        textToolButton.setContentDescription("Text — drag a rectangle on canvas");
        textToolButton.setOnLongClickListener(v -> {
            favorites.showFavoritePopup(v, FavoritesStore.TOOL_TEXT,
                    "Drag text field", () -> {
                        ClipData data = ClipData.newPlainText("canvas-asset", "textfield");
                        View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                        v.startDragAndDrop(data, shadow, "textfield", 0);
                    });
            return true;
        });
        mainRow.addView(pencilButton, iconLp());
        mainRow.addView(eraserButton, iconLp());
        mainRow.addView(lassoButton, iconLp());
        mainRow.addView(textToolButton, iconLp());
        stopwatchButton = iconBtn(R.drawable.ic_timer, this::toggleStopwatchPanel);
        stopwatchButton.setContentDescription("Stopwatch");
        mainRow.addView(stopwatchButton, iconLp());

        // Pencil-only: color swatches + thickness.
        LinearLayout pencilOpts = new LinearLayout(this);
        pencilOpts.setOrientation(LinearLayout.HORIZONTAL);
        pencilOpts.setGravity(Gravity.CENTER_VERTICAL);
        pencilOptions = pencilOpts;

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < INK_COLORS.length; i++) {
            final int index = i;
            View swatch = roundSwatch(penTools.palette()[i], false);
            swatch.setTag(FavoritesStore.colorId(index));
            swatch.setOnClickListener(v -> {
                // A second tap on the colour in use opens the favourite colours for it.
                boolean pencil = !eraserSelected && !lassoSelected && !textSelected;
                if (pencil && index == selectedColorIndex) colorPanels.showColorFavoritesPanel(index);
                else penTools.selectPencil(index);
            });
            swatch.setOnLongClickListener(v -> {
                favorites.showFavoritePopup(v, FavoritesStore.colorId(index),
                        "Edit color", () -> colorPanels.showColorFavoritesPanel(index));
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(SWATCH_SIZE), dp(SWATCH_SIZE));
            lp.setMargins(0, 0, dp(SPACE_SM), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            colorRow.addView(swatch, lp);
            colorButtons.add(swatch);
        }
        pencilOpts.addView(colorRow);

        // Three quick sizes; the menu behind the pen icon tunes them.
        LinearLayout sizeRow = penTools.sizePresetRow(false);
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeLp.setMargins(dp(SPACE_SM), 0, 0, 0);
        pencilOpts.addView(sizeRow, sizeLp);

        // Stroke feel (smoothing + pressure) lives behind a quiet pen icon so the
        // option row stays colour + thickness only.
        penSettingsButton = iconBtn(R.drawable.ic_tune, penTools::togglePenSettingsPopup);
        penSettingsButton.setContentDescription("Pen settings");
        penTools.stylePenSettingsButton();
        LinearLayout.LayoutParams penLp = iconLp();
        penLp.setMargins(dp(SPACE_MD), 0, 0, 0);
        pencilOpts.addView(penSettingsButton, penLp);

        LinearLayout.LayoutParams pencilLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pencilLp.setMargins(dp(SPACE_SM), 0, 0, 0);
        optionRow.addView(pencilOpts, pencilLp);

        // Eraser / lasso: target toggles.
        LinearLayout targets = new LinearLayout(this);
        targets.setOrientation(LinearLayout.HORIZONTAL);
        targets.setGravity(Gravity.CENTER_VERTICAL);
        targetOptions = targets;
        // Lasso: quick toggles for what it picks up, and its menu.
        chipInk = iconBtn(R.drawable.ic_ink, () -> {
            if (canvas == null) return;
            canvas.setLassoInk(!canvas.isLassoInk());
            penTools.refreshLassoChips();
            persistence.scheduleSave();
        });
        chipInk.setContentDescription("Select handwriting");
        chipHl = iconBtn(R.drawable.ic_draw, () -> {
            if (canvas == null) return;
            canvas.setLassoHighlighter(!canvas.isLassoHighlighter());
            penTools.refreshLassoChips();
            persistence.scheduleSave();
        });
        chipHl.setContentDescription("Select highlighter");
        chipText = iconBtn(R.drawable.ic_text, () -> {
            if (canvas == null) return;
            canvas.setLassoText(!canvas.isLassoText());
            penTools.refreshLassoChips();
            persistence.scheduleSave();
        });
        chipText.setContentDescription("Select text boxes");
        chipImg = iconBtn(R.drawable.ic_image, () -> {
            if (canvas == null) return;
            canvas.setLassoImages(!canvas.isLassoImages());
            penTools.refreshLassoChips();
            persistence.scheduleSave();
        });
        chipImg.setContentDescription("Select images");
        targets.addView(chipInk, iconLp());
        targets.addView(chipHl, iconLp());
        targets.addView(chipText, iconLp());
        targets.addView(chipImg, iconLp());
        lassoSettingsButton = iconBtn(R.drawable.ic_tune, penTools::toggleLassoSettingsPopup);
        lassoSettingsButton.setContentDescription("Lasso settings");
        LinearLayout.LayoutParams lsLp = iconLp();
        lsLp.setMargins(dp(SPACE_MD), 0, 0, 0);
        targets.addView(lassoSettingsButton, lsLp);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        targetLp.setMargins(dp(SPACE_SM), 0, 0, 0);
        optionRow.addView(targets, targetLp);

        // Eraser: three quick sizes and its menu (size, what it erases).
        LinearLayout eraserOpts = new LinearLayout(this);
        eraserOpts.setOrientation(LinearLayout.HORIZONTAL);
        eraserOpts.setGravity(Gravity.CENTER_VERTICAL);
        eraserOpts.addView(penTools.sizePresetRow(true));
        eraserSettingsButton = iconBtn(R.drawable.ic_tune, penTools::toggleEraserSettingsPopup);
        eraserSettingsButton.setContentDescription("Eraser settings");
        LinearLayout.LayoutParams eLp = iconLp();
        eLp.setMargins(dp(SPACE_MD), 0, 0, 0);
        eraserOpts.addView(eraserSettingsButton, eLp);
        eraserOptions = eraserOpts;
        optionRow.addView(eraserOpts, targetLp);

        // Text tool: defaults for the next text box (font, bold/italic, colour, size).
        textDefaultsOptions = textTools.buildTextDefaultsRow();
        textDefaultsOptions.setVisibility(View.GONE);
        LinearLayout.LayoutParams textDefLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textDefLp.setMargins(dp(SPACE_SM), 0, 0, 0);
        optionRow.addView(textDefaultsOptions, textDefLp);

        // Stopwatch panel: shown in the option row while its tool button is selected.
        stopwatch = new StopwatchPanel(this, dp(ICON_SIZE));
        stopwatch.setOnChanged(this::onStopwatchChanged);
        stopwatch.setOnWeek(() -> new StudyWeekDialog(this).show());
        stopwatch.setVisibility(View.GONE);
        LinearLayout.LayoutParams stopwatchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stopwatchLp.setMargins(0, 0, dp(SPACE_SM), 0);
        optionRow.addView(stopwatch, stopwatchLp);
        applyStopwatchTheme();

        mainRow.addView(toolDivider());
        openFileButton = iconBtn(R.drawable.ic_folder, () -> {
            hideSoftKeyboard();
            explorer.toggleFolderExplorer();
        });
        openFileButton.setContentDescription("File explorer");
        favorites.bindFavoriteLongPress(openFileButton, FavoritesStore.TOOL_FOLDER);
        mainRow.addView(openFileButton, iconLp());
        // No editor-toggle button: the explorer opens files, which is the only way
        // anyone actually reached the editor. Every use of the field is null-guarded.
        settingsButton = iconBtn(R.drawable.ic_more, overflowMenu::showOverflowMenu);
        settingsButton.setContentDescription("More");
        mainRow.addView(settingsButton, iconLp());
        mainRow.addView(penTools.buildToolExpandButton(), penTools.expandLp());

        scrollRow = new LinearLayout(this);
        scrollRow.setOrientation(LinearLayout.HORIZONTAL);
        scrollRow.setGravity(Gravity.TOP);
        scrollRow.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolScroll.addView(scrollRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolScrollLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolScrollLp.gravity = Gravity.TOP | Gravity.START;
        toolScrollLp.topMargin = statusBarHeight() + dp(SPACE_MD);
        frame.addView(toolScroll, toolScrollLp);
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateToolPillPosition());
        toolScroll.post(this::updateToolPillPosition);

        // Floating selection actions — anchored above lasso / selection.
        selectionActions = new LinearLayout(this);
        selectionActions.setOrientation(LinearLayout.HORIZONTAL);
        selectionActions.setGravity(Gravity.CENTER_VERTICAL);
        selectionActions.setPadding(dp(SPACE_SM), dp(SPACE_XS), dp(SPACE_SM), dp(SPACE_XS));
        selectionActions.setVisibility(View.GONE);
        selectionActions.setElevation(dp(8));
        selectionActions.setClipToOutline(true);
        selectionActionsBg = new GradientDrawable();
        selectionActionsBg.setCornerRadius(dp(999));
        selectionActionsBg.setColor(M3_SURFACE_CONTAINER_HIGHEST);
        selectionActions.setBackground(selectionActionsBg);
        addToChatButton = iconBtn(R.drawable.ic_add_chat, conversations::addSelectionScreenshotToChat);
        copySelButton = iconBtn(R.drawable.ic_copy, () -> {
            if (canvas != null) {
                canvas.copySelection();
                persistence.scheduleSave();
            }
        });
        cutSelButton = iconBtn(R.drawable.ic_cut, () -> {
            if (canvas != null && canvas.cutSelection()) persistence.scheduleSave();
        });
        cutSelButton.setContentDescription("Cut (long-press the canvas to paste)");
        editSelButton = iconBtn(R.drawable.ic_edit, () -> {
            if (canvas == null) return;
            String textId = canvas.getSoleSelectedTextFieldId();
            if (textId != null) textTools.openTextFieldEditor(textId);
        });
        deleteSelButton = iconBtn(R.drawable.ic_delete, () -> {
            if (canvas != null) canvas.deleteSelection();
        });
        selectionActions.addView(editSelButton, iconLp());
        selectionActions.addView(addToChatButton, iconLp());
        selectionActions.addView(cutSelButton, iconLp());
        selectionActions.addView(copySelButton, iconLp());
        // Hidden in presentation: shown on the tablet, left off the slide.
        presentHideButton = iconBtn(R.drawable.ic_visibility, () -> {
            if (canvas == null || !canvas.hasActiveSelection()) return;
            boolean hidden = canvas.togglePresentHidden();
            refreshPresentHideButton();
            persistence.scheduleSave();
            snackbar(hidden ? "Hidden in the presentation"
                    : "Shown in the presentation", false);
        });
        selectionActions.addView(presentHideButton, iconLp());
        selectionActions.addView(deleteSelButton, iconLpLast());
        // Recolour: the palette's swatches, after a divider, when the selection holds
        // handwriting or plain text.
        selectionColorRow = new LinearLayout(this);
        selectionColorRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionColorRow.setGravity(Gravity.CENTER_VERTICAL);
        selectionColorRow.addView(toolDivider());
        selectionColorSwatches.clear();
        for (int i = 0; i < INK_COLORS.length; i++) {
            final int slot = i;
            View sw = roundSwatch(penTools.palette()[i], false);
            sw.setContentDescription("Recolour selection");
            sw.setOnClickListener(v -> swatchTap(v, slot, () -> {
                if (canvas != null && canvas.recolorSelection(penTools.palette()[slot], penTools.inkNameFor(slot))) {
                    refreshSelectionColorSwatches();
                    persistence.scheduleSave();
                }
            }, c -> {
                if (canvas != null) canvas.recolorSelection(c, penTools.inkNameFor(slot));
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(SWATCH_SIZE), dp(SWATCH_SIZE));
            lp.setMargins(i == 0 ? dp(SPACE_SM) : 0, 0, dp(SPACE_SM), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            selectionColorRow.addView(sw, lp);
            selectionColorSwatches.add(sw);
        }
        selectionColorRow.setVisibility(View.GONE);
        selectionActions.addView(selectionColorRow);
        selectionActionsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        selectionActionsLp.gravity = Gravity.TOP | Gravity.START;
        frame.addView(selectionActions, selectionActionsLp);

        // Floating text style bar — shown above the selected text field.
        HorizontalScrollView textStyleScroll = new HorizontalScrollView(this);
        textStyleScroll.setHorizontalScrollBarEnabled(false);
        textStyleScroll.setFillViewport(false);
        textStyleScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        textStyleScroll.setVisibility(View.GONE);
        textStyleScroll.setElevation(dp(8));
        textStyleInner = new LinearLayout(this);
        textStyleInner.setOrientation(LinearLayout.HORIZONTAL);
        textStyleInner.setGravity(Gravity.CENTER_VERTICAL);
        textStyleInner.setPadding(dp(SPACE_SM), dp(SPACE_XS), dp(SPACE_SM), dp(SPACE_XS));
        textStyleBarBg = new GradientDrawable();
        textStyleBarBg.setCornerRadius(dp(999));
        textStyleBarBg.setColor(M3_SURFACE_CONTAINER_HIGHEST);
        textStyleInner.setBackground(textStyleBarBg);
        textStyleInner.setClipToOutline(true);
        fontCycleChip = textTools.miniChip("Sans", textTools::cycleSelectedTextFont);
        textStyleInner.addView(fontCycleChip);
        textStyleInner.addView(textTools.miniChip("B", () -> textTools.toggleSelectedTextBold()));
        textStyleInner.addView(textTools.miniChip("I", () -> textTools.toggleSelectedTextItalic()));
        // The theme's text colour first (the way back to plain), then the ink palette.
        textColorSwatches.clear();
        for (int i = 0; i <= INK_COLORS.length; i++) {
            final int slot = i;
            View sw = roundSwatch(textTools.textSwatchColor(slot), false);
            sw.setOnClickListener(v -> {
                if (slot == 0) textTools.applySelectedTextColor(textTools.textSwatchColor(0));
                else swatchTap(v, slot - 1, () -> textTools.applySelectedTextColor(textTools.textSwatchColor(slot)),
                        textTools::applySelectedTextColor);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(SWATCH_SIZE), dp(SWATCH_SIZE));
            lp.setMargins(0, 0, dp(SPACE_SM), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            textStyleInner.addView(sw, lp);
            textColorSwatches.add(sw);
        }
        textSizeBar = new Material3Slider(this);
        textSizeBar.setMin((int) CanvasTextField.MIN_TEXT_SIZE);
        textSizeBar.setMax(72);
        textSizeBar.setProgress(15);
        textSizeBar.setPadding(dp(SPACE_SM), dp(SPACE_SM), dp(SPACE_SM), dp(SPACE_SM));
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(dp(100), dp(36));
        tslp.gravity = Gravity.CENTER_VERTICAL;
        tintSeekBar(textSizeBar);
        textSizeBar.setOnTouchListener((v, event) -> {
            ViewParent parent = v.getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
                parent = parent.getParent();
            }
            return false;
        });
        textSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || canvas == null) return;
                CanvasTextField tf = textTools.textFieldForStyleUi();
                if (tf == null) return;
                float size = Math.max(CanvasTextField.MIN_TEXT_SIZE, progress);
                canvas.updateTextFieldStyle(tf.id, tf.fontFamily, tf.typefaceStyle, size, tf.color);
                if (inlineEditor != null && inlineEditor.isActive()) inlineEditor.refreshStyle();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { persistence.scheduleSave(); }
        });
        textStyleInner.addView(textSizeBar, tslp);
        // Dictate into the text box (transcribed on the Mac, like the chat's mic).
        inlineMicButton = iconBtn(R.drawable.ic_mic, dictation::toggleTextBoxDictation);
        inlineMicButton.setContentDescription("Dictate into this text box");
        if (!aiEnabled) inlineMicButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams imLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        imLp.gravity = Gravity.CENTER_VERTICAL;
        textStyleInner.addView(inlineMicButton, imLp);
        inlineMicSpinner = new android.widget.ProgressBar(this);
        inlineMicSpinner.setIndeterminate(true);
        inlineMicSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(M3_PRIMARY));
        inlineMicSpinner.setPadding(dp(6), dp(6), dp(6), dp(6));
        inlineMicSpinner.setVisibility(View.GONE);
        textStyleInner.addView(inlineMicSpinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        textStyleScroll.addView(textStyleInner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textStyleBarLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textStyleBarLp.gravity = Gravity.TOP | Gravity.START;
        frame.addView(textStyleScroll, textStyleBarLp);
        textStyleFloatingBar = textStyleScroll;

        undoScrub = new UndoScrubView(this);
        undoScrub.setVisibility(View.GONE);
        // Above the tool bars and any overlay added to the frame later.
        undoScrub.setElevation(dp(32));
        undoScrubHost = frame;
        frame.addView(undoScrub, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return frame;
    }

    void refreshSelectionActions(boolean hasSelection) {
        if (selectionActions == null) return;
        if (canvas != null && canvas.isNavigating()) {
            selectionActions.setVisibility(View.GONE);
            if (textStyleFloatingBar != null) textStyleFloatingBar.setVisibility(View.GONE);
            return;
        }
        if (canvas != null && canvas.selectionIsOnlyContentBlocks()) {
            selectionActions.setVisibility(View.GONE);
            if (textStyleFloatingBar != null) textStyleFloatingBar.setVisibility(View.GONE);
            return;
        }
        boolean canRegion = canvas != null && canvas.canAddRegionToChat();
        CanvasTextField styleTf = textTools.textFieldForStyleUi();
        boolean showText = styleTf != null;
        boolean showActions = hasSelection || canRegion;
        selectionActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
        if (presentHideButton != null) {
            presentHideButton.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
            if (hasSelection) refreshPresentHideButton();
        }
        if (selectionColorRow != null) {
            // With a text box selected the text style bar already offers its colours;
            // show the selection's own row only when there is ink to recolour too.
            boolean recolor = hasSelection && canvas != null && canvas.selectionCanRecolor()
                    && (!showText || canvas.selectionHasInk());
            selectionColorRow.setVisibility(recolor ? View.VISIBLE : View.GONE);
            if (recolor) refreshSelectionColorSwatches();
        }
        if (addToChatButton != null) {
            addToChatButton.setVisibility(canRegion && aiEnabled ? View.VISIBLE : View.GONE);
        }
        if (editSelButton != null) {
            boolean canEdit = styleTf != null && (canvas == null || !canvas.isEditingTextField());
            editSelButton.setVisibility(canEdit && hasSelection ? View.VISIBLE : View.GONE);
        }
        if (textStyleFloatingBar != null) {
            textStyleFloatingBar.setVisibility(showText ? View.VISIBLE : View.GONE);
            if (showText) {
                textTools.refreshFontCycleChip();
                textTools.refreshTextColorSwatches(styleTf);
                if (textSizeBar != null) {
                    textSizeBar.setProgress(Math.round(Math.max(CanvasTextField.MIN_TEXT_SIZE, styleTf.textSize)));
                }
            }
        }
        if (showActions || showText) positionFloatingSelectionActions();
    }

    /** Text field that owns the font/style floating bar (selected or mid-edit). */
    private ImageView presentHideButton;

    /** Open eye = on the slide; crossed-out eye = hidden in the presentation. */
    private void refreshPresentHideButton() {
        if (presentHideButton == null || canvas == null) return;
        boolean hidden = canvas.selectionPresentHidden();
        presentHideButton.setImageResource(hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        applyIconSelected(presentHideButton, hidden);
        presentHideButton.setContentDescription(hidden ? "Show in presentation" : "Hide in presentation");
    }

    /** Recolour swatches in the selection action bar (the palette in use). */
    private LinearLayout selectionColorRow;
    private final List<View> selectionColorSwatches = new ArrayList<>();

    private View lastSwatchTapView;
    private long lastSwatchTapAt;

    /**
     * A tap on a colour swatch: {@code single} runs at once; a second tap on the same
     * swatch within a moment opens the favourite colours for its palette slot, like the
     * pen's swatches do. {@code afterAssign} applies the colour picked there.
     */
    void swatchTap(View swatch, int slot, Runnable single, IntConsumer afterAssign) {
        long now = android.os.SystemClock.uptimeMillis();
        if (swatch == lastSwatchTapView && now - lastSwatchTapAt < 400) {
            lastSwatchTapView = null;
            colorPanels.showColorFavoritesPanel(slot, afterAssign);
            return;
        }
        lastSwatchTapView = swatch;
        lastSwatchTapAt = now;
        single.run();
    }

    void refreshSelectionColorSwatches() {
        int[] pal = penTools.palette();
        for (int i = 0; i < selectionColorSwatches.size() && i < pal.length; i++) {
            applyRoundStyle(selectionColorSwatches.get(i), pal[i], false);
        }
    }

    /** Colour swatches in the text style bar: slot 0 is the theme text colour. */
    final List<View> textColorSwatches = new ArrayList<>();

    // ---- Text tool defaults (expanded tool pill) ---------------------------------------

    LinearLayout textDefaultsOptions;
    private void positionFloatingSelectionActions() {
        if (selectionActions == null || selectionActionsLp == null || canvas == null) return;
        if (canvas.isNavigating()
                || canvas.isSelectionGestureActive()
                || canvas.selectionIsOnlyContentBlocks()) {
            selectionActions.setVisibility(View.GONE);
            if (textStyleFloatingBar != null) textStyleFloatingBar.setVisibility(View.GONE);
            return;
        }
        boolean canRegion = canvas.canAddRegionToChat();
        CanvasTextField styleTf = textTools.textFieldForStyleUi();
        boolean showText = styleTf != null;
        boolean showActions = canvas.hasActiveSelection() || canRegion;
        if (!showActions && !showText) {
            selectionActions.setVisibility(View.GONE);
            if (textStyleFloatingBar != null) textStyleFloatingBar.setVisibility(View.GONE);
            return;
        }
        selectionActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
        if (addToChatButton != null) {
            addToChatButton.setVisibility(canRegion && aiEnabled ? View.VISIBLE : View.GONE);
        }
        if (textStyleFloatingBar != null) {
            textStyleFloatingBar.setVisibility(showText ? View.VISIBLE : View.GONE);
            if (showText) textTools.refreshFontCycleChip();
        }
        float[] anchor = canvas.getSelectionToolbarAnchorScreen();
        if (anchor == null && styleTf != null) {
            android.graphics.RectF r = canvas.getTextFieldScreenRect(styleTf.id);
            if (r != null) anchor = new float[]{r.centerX(), r.top};
        }
        if (anchor == null) return;
        int bw = 0;
        int bh = 0;
        if (showActions) {
            selectionActions.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            bw = selectionActions.getMeasuredWidth();
            bh = selectionActions.getMeasuredHeight();
        }
        int gap = dp(SPACE_MD);
        int minY = statusBarHeight() + dp(SPACE_SM);
        int canvasW = canvas.getWidth();
        int refW = Math.max(bw, 1);
        int maxX = Math.max(0, canvasW - refW - dp(SPACE_SM));

        int textStyleH = 0;
        int textStyleW = 0;
        boolean showTextStyle = textStyleFloatingBar != null
                && textStyleFloatingBar.getVisibility() == View.VISIBLE;
        if (showTextStyle && textStyleBarLp != null) {
            textStyleFloatingBar.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            textStyleW = textStyleFloatingBar.getMeasuredWidth();
            textStyleH = textStyleFloatingBar.getMeasuredHeight();
        }

        int stackH = (showActions ? bh : 0)
                + (showTextStyle ? textStyleH + (showActions ? gap : 0) : 0);

        int y = Math.round(anchor[1] - stackH - gap);
        y = Math.max(minY, y);
        int clear = dp(56);
        int preferRight = Math.round(anchor[0] + clear);
        int preferLeft = Math.round(anchor[0] - clear - Math.max(bw, textStyleW));
        int x;
        if (preferRight <= maxX) {
            x = preferRight;
        } else if (preferLeft >= dp(SPACE_SM)) {
            x = preferLeft;
        } else {
            x = Math.max(dp(SPACE_SM), Math.min(Math.round(anchor[0] - refW * 0.5f), maxX));
        }

        if (showTextStyle && textStyleBarLp != null) {
            int textX = showActions
                    ? x + (bw - textStyleW) / 2
                    : Math.round(anchor[0] - textStyleW / 2f);
            textX = Math.max(dp(SPACE_SM), Math.min(textX, Math.max(0, canvasW - textStyleW - dp(SPACE_SM))));
            textStyleBarLp.leftMargin = textX;
            textStyleBarLp.topMargin = y;
            textStyleFloatingBar.setLayoutParams(textStyleBarLp);
            textStyleFloatingBar.bringToFront();
            y += textStyleH + gap;
        }

        if (showActions) {
            selectionActionsLp.leftMargin = Math.max(dp(SPACE_SM), Math.min(x, maxX));
            selectionActionsLp.topMargin = y;
            selectionActions.setLayoutParams(selectionActionsLp);
            selectionActions.bringToFront();
        }
    }


    void tintSeekBar(SeekBar bar) {
        if (bar instanceof Material3Slider) {
            ((Material3Slider) bar).applyColors(M3_PRIMARY, M3_SURFACE_CONTAINER_HIGHEST);
            return;
        }
        if (bar == null || Build.VERSION.SDK_INT < 21) return;
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(M3_PRIMARY));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(M3_PRIMARY));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(M3_OUTLINE_VARIANT));
    }

    @Override
    public void reportChatTouchScrollX(boolean canScrollX) {
        chatWebTouchScrollXState = canScrollX ? 2 : 1;
    }

    /** Width the open panels may take together; the rest stays canvas. */
    private int panelBudget() {
        return getResources().getDisplayMetrics().widthPixels - dp(MIN_FREE_CANVAS_W);
    }

    private int openPanelsWidth() {
        int used = 0;
        if (!chatCollapsed) used += chatView.chatPanelWidth();
        if (!explorerCollapsed) used += explorer.explorerPanelWidth();
        if (!editorCollapsed) used += codeEditor.editorPanelWidth();
        return used;
    }

    void rebudgetPanelWidths() {
        rebudgetPanelWidths(null);
    }

    /**
     * Makes the open panels fit beside each other, then lays them out. When they do
     * not fit, {@code grower} (the panel being dragged or opened) pushes the other
     * side narrower — the editor first, as it sits against the canvas, then the
     * explorer; the chat for the explorer/editor side — and only stops itself once
     * that side is at its minimum. Pushed widths are stored, so dragging back does
     * not make the other side spring wide again.
     *
     * @param grower the panel growing or opening, or null; while it is the one
     *               about to slide, its position is left to that slide.
     */
    void rebudgetPanelWidths(View grower) {
        // Phone: the panels cover the canvas, so there is nothing to squeeze.
        int over = compactScreen() ? 0 : openPanelsWidth() - panelBudget();
        if (over > 0) {
            if (grower == chatPanel) {
                over -= shrinkEditor(over);
                over -= shrinkExplorer(over);
            } else if (grower == explorerPanel || grower == editorPanel) {
                over -= shrinkChat(over);
            } else {
                over -= shrinkChat(over);
                over -= shrinkEditor(over);
                over -= shrinkExplorer(over);
            }
            // The other side is as narrow as it goes: the grower stops here.
            if (over > 0) {
                if (grower == chatPanel) shrinkChat(over);
                else if (grower == explorerPanel) shrinkExplorer(over);
                else if (grower == editorPanel) shrinkEditor(over);
            }
        }
        fitPanelWidth(explorerPanel, explorerPanelLp, explorer.explorerPanelWidth(), explorerCollapsed,
                explorerSlideAnim, grower, explorer::explorerClosedTranslation);
        fitPanelWidth(editorPanel, editorPanelLp, codeEditor.editorPanelWidth(), editorCollapsed,
                editorSlideAnim, grower, codeEditor::editorClosedTranslation);
        if (!chatResizing) {
            fitPanelWidth(chatPanel, chatPanelLp, chatView.chatPanelWidth(), chatCollapsed,
                    chatSlideAnim, grower, chatView::chatClosedTranslation);
        }
        if (grower != editorPanel) codeEditor.syncEditorOffset();
        chatView.positionChatResizeHandle();
        chatView.syncChatResizeHandleVisibility();
        codeEditor.syncEditorResizeHandleVisibility();
        explorer.syncExplorerResizeHandleVisibility();
    }

    /** Narrows the open chat by up to {@code by}; returns how much it gave. */
    private int shrinkChat(int by) {
        if (by <= 0 || chatCollapsed) return 0;
        int cur = chatView.chatPanelWidth();
        int next = Math.max(dp(CHAT_PANEL_MIN_W), cur - by);
        chatPanelWidthPx = next;
        return cur - next;
    }

    private int shrinkEditor(int by) {
        if (by <= 0 || editorCollapsed) return 0;
        int cur = codeEditor.editorPanelWidth();
        int next = Math.max(dp(EDITOR_PANEL_MIN_W), cur - by);
        editorPanelWidthPx = next;
        return cur - next;
    }

    private int shrinkExplorer(int by) {
        if (by <= 0 || explorerCollapsed) return 0;
        int cur = explorer.explorerPanelWidth();
        int next = Math.max(dp(EXPLORER_PANEL_MIN_W), cur - by);
        explorerPanelWidthPx = next;
        return cur - next;
    }

    private void fitPanelWidth(View panel, ViewGroup.LayoutParams lp, int w, boolean collapsed,
                               ValueAnimator slide, View toggling,
                               java.util.function.Supplier<Float> closedTranslation) {
        if (panel == null || lp == null || lp.width == w) return;
        lp.width = w;
        panel.setLayoutParams(lp);
        // A hidden panel stays hidden at its new width. Never fight a running slide,
        // and leave the panel being toggled to the slide about to start.
        if (collapsed && panel != toggling && (slide == null || !slide.isRunning())) {
            panel.setTranslationX(closedTranslation.get());
        }
    }


    void applyAppTheme(ThemeConfig.AppTheme theme) {
        if (theme == null) theme = ThemeConfig.APP_THEMES[0];
        appThemeId = theme.id;
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString("appThemeId", appThemeId).apply();
        } catch (Exception ignored) {
        }
        M3_SURFACE = theme.surface;
        M3_SURFACE_CONTAINER = theme.surfaceContainer;
        M3_SURFACE_CONTAINER_HIGH = theme.surfaceContainerHigh;
        M3_SURFACE_CONTAINER_HIGHEST = theme.surfaceContainerHighest;
        M3_ON_SURFACE = theme.onSurface;
        M3_ON_SURFACE_VARIANT = theme.onSurfaceVariant;
        M3_PRIMARY = theme.primary;
        M3_PRIMARY_CONTAINER = theme.primaryContainer;
        M3_ON_PRIMARY_CONTAINER = theme.onPrimaryContainer;
        M3_SECONDARY_CONTAINER = theme.secondaryContainer;
        M3_OUTLINE_VARIANT = theme.outlineVariant;
        M3Dialog.setPalette(M3_SURFACE_CONTAINER_HIGH, M3_ON_SURFACE, M3_ON_SURFACE_VARIANT,
                M3_PRIMARY, M3_PRIMARY_CONTAINER, M3_ON_PRIMARY_CONTAINER,
                M3_SURFACE_CONTAINER_HIGHEST, M3_OUTLINE_VARIANT);

        if (rootLayout != null) rootLayout.setBackgroundColor(M3_SURFACE);
        if (centerPane != null) centerPane.setBackgroundColor(M3_SURFACE);
        if (navPillBg != null) {
            navPillBg.setColor(M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
            navPillBg.setCornerRadius(dp(TOOL_PILL_RADIUS));
            if (navPill != null) navPill.setBackground(navPillBg);
        }
        if (toolPillBg != null) {
            toolPillBg.setColor(M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
            toolPillBg.setCornerRadius(dp(TOOL_PILL_RADIUS));
            if (toolPill != null) toolPill.setBackground(toolPillBg);
        }
        if (selectionActionsBg != null) {
            selectionActionsBg.setColor(M3_SURFACE_CONTAINER_HIGHEST);
            selectionActionsBg.setCornerRadius(dp(999));
            if (selectionActions != null) selectionActions.setBackground(selectionActionsBg);
        }
        if (chatPanel != null) {
            chatPanel.setBackgroundColor(0x00000000);
            chatPanel.setElevation(0f);
        }
        if (chatShell != null) chatShell.setBackgroundColor(0x00000000);
        if (chatContentCol != null) chatContentCol.setBackgroundColor(0x00000000);
        chatView.refreshChatPanelBackground();
        if (chatResizeHandle != null) chatView.refreshChatHandleLook();
        refreshSidePanelHandleLook(editorResizeHandle, editorResizeGrip);
        refreshSidePanelHandleLook(explorerResizeHandle, explorerResizeGrip);
        if (chatTabsDrawer != null) chatTabsDrawer.setBackgroundColor(M3_SURFACE_CONTAINER_HIGHEST);
        if (chatTabsDrawerTitle != null) chatTabsDrawerTitle.setTextColor(M3_ON_SURFACE);
        if (chatDragHeaderTitle != null) chatDragHeaderTitle.setTextColor(M3_ON_SURFACE);
        if (chatNewFab != null) {
            applyIconSelected(chatNewFab, true);
            chatView.styleChatHeaderFabShadow(chatNewFab);
        }
        if (chatMoreFab != null) {
            applyIconSelected(chatMoreFab, true);
            chatView.styleChatHeaderFabShadow(chatMoreFab);
        }
        if (chatCanvasFab != null) applyIconSelected(chatCanvasFab, true);
        explorer.refreshExplorerPanelBackground();
        codeEditor.refreshEditorPanelBackground();
        codeEditor.refreshEditorChrome();
        if (folderExplorer != null) {
            folderExplorer.applyTheme(
                    M3_SURFACE_CONTAINER,
                    M3_ON_SURFACE,
                    M3_ON_SURFACE_VARIANT,
                    M3_PRIMARY,
                    M3_PRIMARY_CONTAINER,
                    M3_ON_PRIMARY_CONTAINER,
                    M3_OUTLINE_VARIANT);
        }
        projects.applyAllProjectsTheme();
        applyStopwatchTheme();
        instantChat.applyMiniChatTheme();
        if (canvas != null) {
            canvas.applyFavoritesRadialColors(
                    M3_SURFACE, M3_SURFACE_CONTAINER_HIGHEST, M3_PRIMARY,
                    M3_PRIMARY_CONTAINER, M3_ON_PRIMARY_CONTAINER,
                    M3_ON_SURFACE, M3_ON_SURFACE_VARIANT, M3_OUTLINE_VARIANT);
        }
        if (chatHeaderRule != null) chatHeaderRule.setBackgroundColor((M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x55000000);
        if (chatRunBanner != null) {
            GradientDrawable bannerBg = new GradientDrawable();
            bannerBg.setCornerRadius(dp(10));
            bannerBg.setColor(M3_PRIMARY_CONTAINER);
            chatRunBanner.setBackground(bannerBg);
        }
        if (chatRunBannerLabel != null) chatRunBannerLabel.setTextColor(M3_ON_PRIMARY_CONTAINER);
        if (chatRunBannerSpinner != null && Build.VERSION.SDK_INT >= 21) {
            chatRunBannerSpinner.getIndeterminateDrawable().setColorFilter(
                    new PorterDuffColorFilter(M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        }
        applyPdfSelectionColors();
        if (chatInput != null) {
            chatInput.setTextColor(M3_ON_SURFACE);
            chatInput.setHintTextColor(M3_ON_SURFACE_VARIANT);
        }
        if (chatComposerShell != null) {
            GradientDrawable composerBg = new GradientDrawable();
            composerBg.setCornerRadius(dp(CHAT_COMPOSER_RADIUS));
            composerBg.setColor(M3_SURFACE_CONTAINER_HIGHEST);
            chatComposerShell.setBackground(composerBg);
        }
        if (chatWeb != null) {
            chatWeb.setBackgroundColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 21) chatWeb.setBackground(null);
        }
        if (textStyleBarBg != null) {
            textStyleBarBg.setColor(M3_SURFACE_CONTAINER_HIGHEST);
            if (textStyleInner != null) textStyleInner.setBackground(textStyleBarBg);
        }
        textTools.refreshTextStyleChips();
        textTools.applyThemeToDefaultsRow();
        refreshLearningBadge();
        tintSeekBar(thicknessSeekBar);
        tintSeekBar(textSizeBar);
        penTools.refreshSizeDots();
        penTools.refreshLassoChips();
        for (View div : toolDividers) {
            if (div == null) continue;
            GradientDrawable d = new GradientDrawable();
            d.setColor(M3_OUTLINE_VARIANT);
            d.setCornerRadius(dp(1));
            div.setBackground(d);
        }
        conversations.refreshAttachRow();
        // Keep an open settings dialog's colours in sync.
        settingsPanel.repopulateOptionsCard();

        if (canvas != null) {
            canvas.applyAppTheme(theme);
        }
        if (latexRenderer != null) {
            latexRenderer.applyTheme(theme);
            if (canvas != null) canvas.refreshAllLatexTextFields();
        }

        // Keep syntax colors locked to the same appearance.
        applyCodeStyle(theme.code);

        // Refresh icon chrome + tabs that use theme colors.
        penTools.refreshToolSelection();
        conversations.refreshChatTabs();
        chatView.applyChatWebTheme(theme);
        if (sendButton != null) applyIconSelected(sendButton, true);
        if (stopButton != null) applyIconSelected(stopButton, true);
        if (chatAttachButton != null) applyIconSelected(chatAttachButton, false);
        if (chatMicDiscardButton != null) applyIconSelected(chatMicDiscardButton, false);
        refreshHoverFeedback();
        if (chatVoiceWave != null) chatVoiceWave.setColor(M3_PRIMARY);
        dictation.restyleForTheme();
        if (addToChatButton != null) applyIconSelected(addToChatButton, false);
        if (copySelButton != null) applyIconSelected(copySelButton, false);
        if (cutSelButton != null) applyIconSelected(cutSelButton, false);
        // Icons created as locals (pages, cut, …) never got re-tinted on a theme change,
        // so they kept the launch theme's colour next to correctly tinted neighbours.
        retintIdleIcons(navPill);
        retintIdleIcons(selectionActions);
        if (presentHideButton != null && canvas != null && canvas.hasActiveSelection()) {
            refreshPresentHideButton();
        }
        if (editSelButton != null) applyIconSelected(editSelButton, false);
        if (deleteSelButton != null) applyIconSelected(deleteSelButton, false);
        if (openFileButton != null) applyIconSelected(openFileButton, !explorerCollapsed);
        if (editorToggleButton != null) applyIconSelected(editorToggleButton, !editorCollapsed);
        if (settingsButton != null) applyIconSelected(settingsButton, false);
        // Walk the left pill rather than naming its buttons: this list is where an
        // icon gets forgotten, and the recents button was — it kept the placeholder
        // tint from before the saved theme loaded, so the pill held two greys.
        if (navPill != null) {
            for (int i = 0; i < navPill.getChildCount(); i++) {
                View child = navPill.getChildAt(i);
                if (child instanceof ImageView) applyIconSelected((ImageView) child, false);
            }
        }
        penTools.stylePenSettingsButton();
        penTools.styleToolExpandButton();
    }

    private void applyCodeStyle(ThemeConfig.CodeStyle style) {
        if (style == null) style = ThemeConfig.CODE_STYLES[0];
        codeStyleId = style.id;
        if (canvas != null) canvas.applyCodeStyle(style);
        codeEditor.refreshEditorCodeStyle();
    }

    static String toCssColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a >= 255) return String.format("#%02X%02X%02X", r, g, b);
        return String.format("rgba(%d,%d,%d,%.3f)", r, g, b, a / 255f);
    }

    /** Stopwatch tool: a main-row button whose options-row panel holds the controls. */
    StopwatchPanel stopwatch;
    /** When the stopwatch ran, for the study schedule. */
    StudyLog studyLog;
    private ImageView stopwatchButton;
    /**
     * Showing the stopwatch panel in the option row. Not a canvas tool — the pen keeps
     * drawing with whichever tool was active, so notes can be taken while it runs.
     */
    boolean stopwatchSelected;

    private void applyStopwatchTheme() {
        if (stopwatch != null) {
            stopwatch.setColors(M3_ON_SURFACE, M3_PRIMARY_CONTAINER,
                    M3_ON_PRIMARY_CONTAINER, M3_SECONDARY_CONTAINER);
        }
        refreshStopwatchButton();
    }

    private void toggleStopwatchPanel() {
        stopwatchSelected = !stopwatchSelected;
        // Its controls live in the option row, so opening it from a collapsed bar
        // expands the bar too.
        if (stopwatchSelected && !toolOptionsExpanded) penTools.setToolOptionsExpanded(true);
        penTools.refreshToolSelection();
    }

    /** Selected while its panel is up; tinted primary while it runs out of sight. */
    /** The stopwatch started or stopped: the study log follows it. */
    private void onStopwatchChanged() {
        refreshStopwatchButton();
        if (studyLog == null) return;
        if (stopwatch != null && stopwatch.isRunning()) {
            String doc = canvas != null ? canvas.getDocumentPath() : null;
            studyLog.begin(doc);
        } else {
            studyLog.end();
        }
    }

    void refreshStopwatchButton() {
        if (stopwatchButton == null) return;
        applyIconSelected(stopwatchButton, stopwatchSelected);
        if (!stopwatchSelected && stopwatch != null && stopwatch.isRunning()) {
            stopwatchButton.setColorFilter(new PorterDuffColorFilter(M3_PRIMARY, PorterDuff.Mode.SRC_IN));
        }
    }

    void statusToast(String message) {
        snackbar(message, true);
    }

    private TextView snackbarView;
    private final Runnable snackbarHide = () -> {
        TextView v = snackbarView;
        if (v == null) return;
        v.animate().alpha(0f).translationY(dp(16)).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> {
                    if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                    if (snackbarView == v) snackbarView = null;
                }).start();
    };

    /**
     * Material 3 snackbar in place of the system toast: an inverse-surface pill near
     * the bottom that rises in (landing like the app's cards) and slips away again.
     * A new message while one shows replaces its text with a small pop.
     */
    void snackbar(String message, boolean longer) {
        if (message == null || message.isEmpty()) return;
        if (rootLayout == null || isDead()) return;
        saveHandler.removeCallbacks(snackbarHide);
        TextView v = snackbarView;
        if (v != null && v.getParent() != null) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setText(message);
            Motion.pop(v);
        } else {
            v = new TextView(this);
            v.setText(message);
            v.setTextColor(M3_SURFACE | 0xFF000000);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            v.setMaxLines(3);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setMinHeight(dp(48));
            v.setPadding(dp(SPACE_LG + 4), dp(SPACE_MD), dp(SPACE_LG + 4), dp(SPACE_MD));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(M3_ON_SURFACE | 0xFF000000);
            v.setBackground(bg);
            v.setElevation(dp(6));
            v.setMaxWidth(dp(560));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(SPACE_XL + 8);
            lp.leftMargin = dp(SPACE_XL);
            lp.rightMargin = dp(SPACE_XL);
            v.setTranslationZ(dp(zenMode ? ZEN_LIFT_DP + 80 : 80));
            v.setClickable(true);
            v.setOnClickListener(x -> {
                saveHandler.removeCallbacks(snackbarHide);
                snackbarHide.run();
            });
            rootLayout.addView(v, lp);
            snackbarView = v;
            final TextView shown = v;
            shown.setAlpha(0f);
            shown.post(() -> Motion.popIn(shown, shown.getWidth() / 2f, shown.getHeight(), 20f));
        }
        saveHandler.postDelayed(snackbarHide, longer ? 4000L : 2500L);
    }








    // ---- Zen mode ---------------------------------------------------------------

    /** Canvas lift while in Zen: well above every panel, pill and FAB elevation. */
    static final int ZEN_LIFT_DP = 200;
    boolean zenMode;
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Before Android 13; later versions come through the callback registered in onCreate.
        if (!handleBack()) moveTaskToBack(true);
    }

    /** A full-screen layer that closes when tapped outside its card (menus, pickers, panels). */
    private boolean isBackOverlay(View v) {
        if (v == null || v == centerPane || v == allProjectsView || v.getVisibility() != View.VISIBLE) return false;
        if (!(v instanceof FrameLayout) || !v.hasOnClickListeners()) return false;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                && lp.height == ViewGroup.LayoutParams.MATCH_PARENT;
    }

    /**
     * Android back: closes what is open, the innermost first — a menu or picker, a selection, a
     * side panel — then a document goes back to the library and a folder up a level. False at
     * the top of the library, where the app steps aside instead (it keeps its state).
     */
    boolean handleBack() {
        if (isDead() || rootLayout == null) return false;
        if (zenMode) {
            zen.exitZenMode();
            return true;
        }
        for (int i = rootLayout.getChildCount() - 1; i >= 0; i--) {
            View v = rootLayout.getChildAt(i);
            if (isBackOverlay(v)) {
                v.performClick();
                return true;
            }
        }
        boolean libraryOpen = allProjectsView != null && allProjectsView.getVisibility() == View.VISIBLE;
        if (libraryOpen) return allProjectsView.handleBack();
        if (canvas != null && canvas.hasActiveSelection()) {
            canvas.clearSelection();
            return true;
        }
        if (!editorCollapsed) {
            codeEditor.closeEditorPanel();
            return true;
        }
        if (!explorerCollapsed) {
            explorer.hideFolderExplorer();
            return true;
        }
        if (!chatCollapsed) {
            chatView.toggleChatCollapsed();
            return true;
        }
        if (stopwatchSelected) {
            toggleStopwatchPanel();
            return true;
        }
        // From a document (or the empty canvas) back to the library.
        projects.showAllProjects();
        return true;
    }



    // ---- Size presets and the pen / eraser menus ---------------------------------------

    /** Eraser radius per preset (screen px) and the one in use. */
    final float[] eraserSizes = {14f, 28f, 56f};
    int eraserSlot = 1;
    View eraserOptions;
    View eraserSettingsButton;
    ImageView chipHl;
    ImageView chipText;
    View lassoSettingsButton;
    interface IntConsumer { void accept(int v); }

    private interface IdFn<T> { String id(T t); }
    private interface LabelFn<T> { String label(T t); }
    private interface IdConsumer { void accept(String id); }
    interface BoolConsumer { void accept(boolean v); }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPLOAD_FILES) {
            if (resultCode == RESULT_OK && data != null) transfers.handleUploadResult(data);
            else pendingUploadDir = null;
            return;
        }
        if (requestCode != REQ_PICK_ATTACHMENT || resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final String role = pendingAttachRole;
        if (uris.size() > 1) statusToastShort("Attaching " + uris.size() + " files…");
        // Reading many or large files would freeze the UI; do it on a worker.
        new Thread(() -> {
            final List<PendingAttachment> read = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                String name = conversations.queryDisplayName(uri);
                try {
                    PendingAttachment att = conversations.readAttachment(uri, mime, name);
                    if (att != null) {
                        att.role = role;
                        read.add(att);
                    }
                } catch (Exception e) {
                    failed.add((name != null ? name : "file") + " (" + e.getMessage() + ")");
                }
            }
            runOnUiThread(() -> {
                if (isDead()) return;
                for (PendingAttachment att : read) {
                    boolean dup = false;
                    for (PendingAttachment existing : pendingAttachments) {
                        if (existing.name.equals(att.name) && existing.data != null
                                && existing.data.length == att.data.length) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) pendingAttachments.add(att);
                }
                conversations.refreshAttachRow();
                if (!failed.isEmpty()) {
                    statusToast("Could not attach " + String.join(", ", failed));
                }
            });
        }, "cc-attach").start();
    }

    /** Re-reads the file lists the user may be looking at (explorer, shared PDFs, All Projects). */
    void refreshFileViews() {
        if (folderExplorer != null) {
            folderExplorer.reloadOpenFolders();
            folderExplorer.reloadShared();
        }
        if (allProjectsView != null && allProjectsView.getVisibility() == View.VISIBLE) {
            allProjectsView.reload();
        }
    }

    /** Runs {@code action} once the linked computer has this tablet's latest files (at once without one). */
    void syncThen(Runnable action) {
        if (remoteSync == null) action.run();
        else remoteSync.syncNow(action);
    }

    /** Center tool pill in the free canvas strip; scroll when content is wider. */
    void updateToolPillPosition() {
        if (toolScroll == null || toolScrollLp == null || centerPane == null || toolPill == null) {
            return;
        }
        int paneW = centerPane.getWidth();
        if (paneW <= 0) {
            centerPane.post(this::updateToolPillPosition);
            return;
        }
        int gap = dp(SPACE_LG);
        // The canvas is full width; these are only how much of it a panel hides.
        int leftCover = 0;
        int rightCover = 0;
        if (!compactScreen()) {
            int chatVisible = visibleChatWidthPx();
            int explorerVisible = visibleExplorerWidthPx();
            int editorVisible = codeEditor.visibleEditorWidthPx();
            if (chatOnLeft && chatVisible > 0) leftCover = Math.max(leftCover, chatVisible);
            if (!chatOnLeft && chatVisible > 0) rightCover = Math.max(rightCover, chatVisible);
            if (explorerOnLeft && explorerVisible > 0) {
                leftCover = Math.max(leftCover, explorerVisible);
            }
            if (!explorerOnLeft && explorerVisible > 0) {
                rightCover = Math.max(rightCover, explorerVisible);
            }
            if (explorerOnLeft && editorVisible > 0) {
                leftCover = Math.max(leftCover, editorVisible);
            }
            if (!explorerOnLeft && editorVisible > 0) {
                rightCover = Math.max(rightCover, editorVisible);
            }
        }

        // Free strip in full pane coordinates.
        int freeLeft = leftCover + gap;
        int freeRight = paneW - rightCover - gap;
        int freeW = Math.max(dp(ICON_SIZE * 2), freeRight - freeLeft);

        toolPill.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentW = Math.max(1, Math.max(toolPill.getMeasuredWidth(), toolPill.getWidth()));

        // The nav pill sits at the start of the same strip, on the same row. Centring the
        // tool pill in the strip ignored it, so a narrow strip slid one over the other.
        int navRight = freeLeft;
        int navH = 0;
        if (navPill != null && navPill.getVisibility() == View.VISIBLE) {
            int navW = navPill.getWidth();
            navH = navPill.getHeight();
            if (navW <= 0 || navH <= 0) {
                navPill.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                navW = navPill.getMeasuredWidth();
                navH = navPill.getMeasuredHeight();
            }
            navRight = freeLeft + navW + gap;
        }
        int besideW = freeRight - navRight;
        // The two pills do not fit side by side: they become one bar that scrolls as a whole,
        // instead of the tools sliding under a nav pill that stays put.
        boolean navVisible = navPill != null && navPill.getVisibility() == View.VISIBLE;
        int navOuterW = navRight - freeLeft;
        boolean merged = navVisible && navOuterW + contentW > freeW;
        boolean navInRow = navPill != null && navPill.getParent() == scrollRow;
        if (navPill != null && navInRow != merged && !(navInRow && !navVisible)) {
            // Moving a view between parents is not allowed from inside a layout pass.
            toolScroll.post(() -> {
                if (navPill.getParent() == scrollRow && !merged) {
                    scrollRow.removeView(navPill);
                    toolFrame.addView(navPill, navFrameLp);
                } else if (navPill.getParent() != scrollRow && merged) {
                    ((ViewGroup) navPill.getParent()).removeView(navPill);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = dp(SPACE_LG);
                    navPill.setTranslationX(0f);
                    scrollRow.addView(navPill, 0, lp);
                }
                updateToolPillPosition();
            });
            return;
        }
        boolean ownRow = false;

        // HorizontalScrollView ignores child gravity — center by positioning the
        // scroll view itself when the pill fits; otherwise span the room it has.
        int scrollW;
        int desiredLeft;                     // where the pill's left edge belongs, pane coords
        if (merged) {
            scrollW = freeW;
            desiredLeft = freeLeft;
        } else if (ownRow) {
            if (contentW <= freeW) {
                scrollW = contentW;
                desiredLeft = freeLeft + (freeW - contentW) / 2;
            } else {
                scrollW = freeW;
                desiredLeft = freeLeft;
            }
        } else if (contentW <= besideW) {
            scrollW = contentW;
            // Centred in the whole strip when that clears the nav pill, else beside it.
            desiredLeft = Math.max(navRight, freeLeft + (freeW - contentW) / 2);
            desiredLeft = Math.min(desiredLeft, freeRight - contentW);
        } else {
            scrollW = Math.max(dp(ICON_SIZE * 2), besideW);
            desiredLeft = navRight;
        }

        // The scroll view is padded for the shadows; the pills themselves stay where they were.
        scrollW += 2 * dp(TOOL_SHADOW_PAD);
        desiredLeft -= dp(TOOL_SHADOW_PAD);
        int topMargin = statusBarHeight() + dp(SPACE_MD) - dp(TOOL_SHADOW_TOP);
        if (ownRow) topMargin += navH + gap;
        int gravity = Gravity.TOP | Gravity.START;
        // Horizontal placement is a translation, never a margin. This method runs from
        // centerPane's OnLayoutChangeListener, and a requestLayout raised inside a
        // layout pass is dropped — while the guard below, comparing against the very
        // params object it just mutated, then saw no change and never retried. The pill
        // kept the position the previous layout gave it, one toggle behind, which is
        // what left it off-centre whenever a panel was open.
        if (toolScrollLp.width != scrollW
                || toolScrollLp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                || toolScrollLp.gravity != gravity
                || toolScrollLp.topMargin != topMargin
                || toolScrollLp.leftMargin != 0
                || toolScrollLp.rightMargin != 0) {
            toolScrollLp.width = scrollW;
            toolScrollLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            toolScrollLp.gravity = gravity;
            toolScrollLp.topMargin = topMargin;
            toolScrollLp.leftMargin = 0;
            toolScrollLp.rightMargin = 0;
            // Posted so the request never lands inside the layout pass that called us.
            final HorizontalScrollView target = toolScroll;
            target.post(() -> target.setLayoutParams(toolScrollLp));
        }

        toolScroll.setTranslationX(desiredLeft);
        if (navPill != null && !merged) navPill.setTranslationX(freeLeft);

        if (!merged && contentW <= freeW && toolScroll.getScrollX() != 0) {
            toolScroll.scrollTo(0, 0);
        }
    }

    /** Target left/right padding for settled open panels. */
    private int[] targetCenterPaneInsets() {
        int chatVisible = chatCollapsed ? 0 : chatView.chatPanelWidth();
        int explorerVisible = explorerCollapsed ? 0 : explorer.explorerPanelWidth();
        int editorVisible = 0;
        if (!editorCollapsed) {
            editorVisible = codeEditor.editorPanelWidth()
                    + (explorerCollapsed ? 0 : explorer.explorerPanelWidth());
        }
        // On a phone the panels cover the canvas instead of pushing it aside.
        if (compactScreen()) return new int[]{0, 0};
        int left = 0;
        int right = 0;
        if (chatOnLeft && chatVisible > 0) left = Math.max(left, chatVisible);
        if (!chatOnLeft && chatVisible > 0) right = Math.max(right, chatVisible);
        if (explorerOnLeft) {
            left = Math.max(left, Math.max(explorerVisible, editorVisible));
        } else {
            right = Math.max(right, Math.max(explorerVisible, editorVisible));
        }
        return new int[]{left, right};
    }

    /** True while a side panel is being drag-resized. */
    private boolean sidePanelResizing() {
        return explorerResizing || chatResizing || editorResizing;
    }

    /**
     * Keep the canvas full-bleed.
     *
     * <p>Panels float over it now rather than shrinking it. Everything that used to
     * make a panel change the canvas size is gone with that: the padding, the slide
     * that compensated for it, the single resize deferred to the last animation
     * frame, and the camera re-centre the resize triggered. A view that never
     * changes size cannot jump when a panel opens, and there is nothing left to get
     * the timing of.
     */
    void updateCenterPaneInsets() {
        if (centerPane == null) return;
        if (centerPane.getPaddingLeft() != 0 || centerPane.getPaddingRight() != 0) {
            centerPane.setPadding(0, 0, 0, 0);
        }
        centerPane.setTranslationX(0f);
        updateToolPillPosition();
    }

    /** How much of the chat panel is currently on-screen (accounts for slide translation). */
    private int visibleChatWidthPx() {
        if (chatPanel == null) return 0;
        int w = chatView.chatPanelWidth();
        int visible = w - Math.round(Math.abs(chatPanel.getTranslationX()));
        return Math.max(0, Math.min(w, visible));
    }

    /** How much of the explorer sidebar is currently on-screen. */
    int visibleExplorerWidthPx() {
        if (explorerPanel == null) return 0;
        int w = explorer.explorerPanelWidth();
        int visible = w - Math.round(Math.abs(explorerPanel.getTranslationX()));
        return Math.max(0, Math.min(w, visible));
    }

    @Override
    public String artifactUrl(String relPath) {
        String base = bridge.getBaseUrl();
        if (relPath.startsWith("http")) return relPath;
        String name = relPath.startsWith(".artifacts/") ? relPath.substring(".artifacts/".length()) : relPath;
        return bridge.withToken(base + "/artifacts/" + name);
    }

    @Override
    public void startTextDrag(String text) {
        // Called from the chat page's JavaScript bridge — a background thread. Touching
        // the canvas from there is what crashed dragging chat text in.
        runOnUiThread(() -> {
            if (isDead() || canvas == null || text == null) return;
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return;
            float[] c = canvas.getViewCenterWorld();
            canvas.addPlainTextField(trimmed, c[0], c[1]);
            persistence.scheduleSave();
            canvasAgent.pushCanvasStateToBridge();
        });
    }

    @Override
    public void onChatSelection(String webId, String source, float x, float y) {
        runOnUiThread(() -> chatView.showChatSelectionChip(webId, source, x, y));
    }

    static final int M3_BUTTON_TEXT = 0;
    static final int M3_BUTTON_TONAL = 1;
    static final int M3_BUTTON_FILLED = 2;

    /**
     * The app's button: a 40dp pill — text, tonal or filled — with an optional leading
     * icon. The same shape Settings, Export and the dialogs use.
     */
    TextView m3Button(String label, int kind, int icon, Runnable onTap) {
        int fill = kind == M3_BUTTON_FILLED ? M3_PRIMARY_CONTAINER
                : kind == M3_BUTTON_TONAL ? M3_SURFACE_CONTAINER_HIGHEST : 0x00000000;
        int fg = kind == M3_BUTTON_FILLED ? M3_ON_PRIMARY_CONTAINER
                : kind == M3_BUTTON_TONAL ? M3_ON_SURFACE : M3_PRIMARY;
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setPadding(dp(icon != 0 ? SPACE_LG : kind == M3_BUTTON_TEXT ? SPACE_LG : SPACE_XL), 0,
                dp(kind == M3_BUTTON_TEXT ? SPACE_LG : SPACE_XL), 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
            d.setTint(fg);
            d.setBounds(0, 0, dp(18), dp(18));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(SPACE_SM));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(fill);
        b.setBackground(withHoverRipple(bg, false));
        if (onTap != null) {
            b.setOnClickListener(v -> {
                if (v.isEnabled()) onTap.run();
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.leftMargin = dp(SPACE_SM);
        b.setLayoutParams(lp);
        return b;
    }


    // ---- Explorer: upload into the project, download to the tablet -------------------

    static final int REQ_UPLOAD_FILES = 1002;
    String pendingUploadDir;

    void statusToastShort(String message) {
        snackbar(message, false);
    }

    /**
     * Chat / JS bridge: open an artifact HTML path as a visualization (never source).
     * Ensures visualizations/{stem}.viz exists so the picker shows up in the explorer.
     */
    @Override
    public void openArtifact(String relPath) {
        runOnUiThread(() -> {
            if (relPath == null || relPath.isEmpty()) return;
            codeEditor.ensureEditorPanel();
            Runnable load = () -> {
                String name = relPath;
                if (name.startsWith(".artifacts/")) name = name.substring(".artifacts/".length());
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                final String htmlName = name.toLowerCase(java.util.Locale.US).endsWith(".html")
                        ? name
                        : name + ".html";
                final String stem = htmlName.substring(0, htmlName.length() - 5);
                final String vizPath = "visualizations/" + stem + ".viz";
                // Touch the pointer so the explorer always has an entry.
                workspace.writeFile(vizPath, "", new BridgeClient.Callback<BridgeClient.FileContent>() {
                    @Override
                    public void onSuccess(BridgeClient.FileContent file) {
                        if (isDead()) return;
                        codeEditor.showVizInEditor(vizPath, htmlName);
                        codeEditor.showEditorPanel();
                    }

                    @Override
                    public void onError(String message) {
                        if (isDead()) return;
                        // Still open even if the pointer could not be written.
                        codeEditor.showVizInEditor(vizPath, htmlName);
                        codeEditor.showEditorPanel();
                    }
                });
            };
            if (editorDirty && scriptEditorPath != null && editorVizArtifact == null) {
                codeEditor.saveScriptEditorQuiet(load);
            } else {
                load.run();
            }
        });
    }

    /**
     * Corner radii for side panels. Screen-edge corners stay square; the canvas-facing
     * edge is rounded. When explorer+editor stack, the shared edge stays square so they
     * read as one block with a single rounded face toward the canvas.
     */
    float[] sidePanelCornerRadii(boolean onLeft, boolean roundCanvasEdge, boolean roundScreenEdge) {
        return sidePanelCornerRadii(onLeft, roundCanvasEdge, roundScreenEdge, dp(CHAT_BG_CORNER));
    }

    float[] sidePanelCornerRadii(
            boolean onLeft, boolean roundCanvasEdge, boolean roundScreenEdge, float r) {
        float screen = roundScreenEdge ? r : 0f;
        float canvas = roundCanvasEdge ? r : 0f;
        if (onLeft) {
            // left=screen, right=canvas
            return new float[]{screen, screen, canvas, canvas, canvas, canvas, screen, screen};
        }
        // left=canvas, right=screen
        return new float[]{canvas, canvas, screen, screen, screen, screen, canvas, canvas};
    }

    /** Phone-sized screens (under 600dp wide): side panels cover the canvas, one at a time. */
    boolean compactScreen() {
        return getResources().getConfiguration().screenWidthDp < 600;
    }

    int screenWidthPx() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** A card/dialog width of {@code dpWanted}, but never wider than the screen allows. */
    int cardWidth(int dpWanted) {
        return Math.min(dp(dpWanted), screenWidthPx() - dp(32));
    }

    /** On a phone, opening a side panel puts the others away. */
    void closeOtherPanelsIfCompact(String keep) {
        if (!compactScreen()) return;
        if (!"chat".equals(keep) && !chatCollapsed) chatView.animateChatToCollapsed();
        if (!"explorer".equals(keep) && !explorerCollapsed) explorer.hideFolderExplorer();
        if (!"editor".equals(keep) && !editorCollapsed) codeEditor.closeEditorPanel();
    }

    int clampSidePanelWidth(int w, int min) {
        if (compactScreen()) return screenWidthPx();
        int screen = getResources().getDisplayMetrics().widthPixels;
        int max = Math.max(min + dp(48), Math.round(screen * SIDE_PANEL_MAX_FRAC));
        return Math.max(min, Math.min(max, w));
    }

    void refreshSidePanelHandleLook(View handle, View grip) {
        if (handle != null) handle.setBackgroundColor(0x00000000);
        if (grip != null) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(999));
            pill.setColor(M3_ON_SURFACE_VARIANT);
            grip.setBackground(pill);
            FrameLayout.LayoutParams gripLp = (FrameLayout.LayoutParams) grip.getLayoutParams();
            if (gripLp == null) {
                gripLp = new FrameLayout.LayoutParams(dp(CHAT_RESIZE_PILL_W), dp(CHAT_RESIZE_PILL_H));
            }
            gripLp.width = dp(CHAT_RESIZE_PILL_W);
            gripLp.height = dp(CHAT_RESIZE_PILL_H);
            gripLp.gravity = Gravity.CENTER;
            grip.setLayoutParams(gripLp);
        }
    }

    /** Matches the canvas-pane inset animation so panel and canvas move together. */
    private static final int PANEL_SLIDE_MS = 220;

    /**
     * Slides a side panel to {@code to}. Panels used to snap: 1eabfcf replaced the
     * slide with a bare setTranslationX and animated only the canvas pane, which left
     * the panels themselves popping in and out.
     *
     * @param onUpdate  optional; called each frame after translation is applied
     *                  (used to keep the editor flush with the explorer while it slides)
     * @param onSettled optional; called when the slide finishes (or immediately if
     *                  already at the target)
     */
    ValueAnimator slidePanelTo(View panel, float to, Runnable onUpdate, Runnable onSettled) {
        if (panel == null) return null;
        float from = panel.getTranslationX();
        if (Math.abs(to - from) < 0.5f) {
            panel.setTranslationX(to);
            if (onUpdate != null) onUpdate.run();
            if (onSettled != null) onSettled.run();
            updateToolPillPosition();
            return null;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(PANEL_SLIDE_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            panel.setTranslationX((Float) a.getAnimatedValue());
            if (onUpdate != null) onUpdate.run();
            updateToolPillPosition();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panel.setTranslationX(to);
                if (onUpdate != null) onUpdate.run();
                // Anything gated on the panel being *fully* expanded has to be
                // re-evaluated here. The resize handle only shows when translationX
                // has reached 0, so while the slide was instant a sync at call time
                // was enough; now it runs mid-slide and would hide the handle for
                // good — it only ever appeared on a panel's first open, when the
                // freshly built panel was already at its target and never animated.
                if (onSettled != null) onSettled.run();
                updateToolPillPosition();
            }
        });
        anim.start();
        return anim;
    }

    ValueAnimator slidePanelTo(View panel, float to, Runnable onSettled) {
        return slidePanelTo(panel, to, null, onSettled);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            dictation.startVoiceRecording();
        } else {
            conversations.appendChat("warn", "dictation needs microphone access");
        }
    }

    // ---- Presentation mode: the current page, whole, on a second screen -----------

    SlidePresentation presentation;
    // ---- Instant chat: floating mini chat on the active chat ---------------------

    MiniChatWindow miniChat;
    /**
     * What the instant chat held when it was closed with a send still waiting on the
     * transcript; that send then goes out through the main chat. Null otherwise.
     */
    String miniClosedDraft;
    /** Send the instant chat's text as soon as the running dictation is transcribed. */
    boolean miniSendAfterDictation;

    ImageView iconBtn(int drawableRes, Runnable onClick) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawableRes);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(ICON_PAD), dp(ICON_PAD), dp(ICON_PAD), dp(ICON_PAD));
        v.setClickable(true);
        v.setFocusable(true);
        v.setSoundEffectsEnabled(true);
        applyIconSelected(v, false);
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    LinearLayout.LayoutParams iconLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ICON_SIZE), dp(ICON_SIZE));
        lp.setMargins(0, 0, dp(SPACE_XS), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private LinearLayout.LayoutParams iconLpLast() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ICON_SIZE), dp(ICON_SIZE));
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private View toolDivider() {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(M3_OUTLINE_VARIANT);
        d.setCornerRadius(dp(1));
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(16));
        lp.setMargins(dp(SPACE_SM), 0, dp(SPACE_SM), 0);
        lp.height = dp(20);
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        toolDividers.add(v);
        return v;
    }

    void applyIconSelected(ImageView v, boolean selected) {
        if (v == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (selected) {
            d.setColor(M3_PRIMARY_CONTAINER);
            v.setColorFilter(new PorterDuffColorFilter(M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        } else {
            // No outline — idle icons sit flush on the pill; only tint marks them.
            d.setColor(0x00000000);
            v.setColorFilter(new PorterDuffColorFilter(M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        }
        v.setBackground(withHoverRipple(d, true));
        if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
    }

    // ---- Pen hover feedback everywhere (like the instant chat's buttons) ----------

    /** Hover/press highlight color: on-surface at low alpha, like the instant chat. */
    int hoverTint() {
        return (M3_ON_SURFACE & 0x00FFFFFF) | 0x29000000;
    }

    /** Wrap a button background so a hovering pen (or a press) lights it up. */
    android.graphics.drawable.RippleDrawable withHoverRipple(
            android.graphics.drawable.Drawable content, boolean oval) {
        GradientDrawable mask = new GradientDrawable();
        if (oval) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(dp(12));
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(hoverTint()), content, mask);
    }

    private final java.util.Set<View> hoverDecorated =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean hoverSweepPosted;
    private final Runnable hoverSweep = () -> {
        hoverSweepPosted = false;
        if (rootLayout != null) sweepHover(rootLayout);
    };

    /** After layouts settle, give any new clickable control a hover highlight. */
    private void scheduleHoverSweep() {
        if (hoverSweepPosted) return;
        hoverSweepPosted = true;
        saveHandler.postDelayed(hoverSweep, 400L);
    }

    private final java.util.Set<View> pressMotion =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Small controls (icon buttons, chips, pills, FABs) squeeze a little while pressed
     * and spring back on release. Big surfaces, drag tiles, switches and anything with
     * its own state animator are left alone.
     */
    private void addPressMotion(View v) {
        if (pressMotion.contains(v) || v.getStateListAnimator() != null
                || v.getTag(R.id.motion_no_press) != null || v instanceof Material3Switch
                || v.getScaleX() != 1f || v.getScaleY() != 1f) {
            return;
        }
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        int small = Math.min(w, h);
        int large = Math.max(w, h);
        if (small > dp(64) || large > dp(280)) return;
        pressMotion.add(v);
        // Icon-sized controls move more than wide pills, so both read the same.
        Motion.addPressScale(v, large <= dp(56) ? 0.86f : 0.95f);
    }

    /** Theme changed: re-tint every highlight we added. */
    private void refreshHoverFeedback() {
        for (View v : new ArrayList<>(hoverDecorated)) {
            if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
        }
        hoverDecorated.clear();
        if (rootLayout != null) sweepHover(rootLayout);
    }

    /**
     * Adds a hover foreground to small clickable leaf controls that lack one.
     * Returns whether this subtree contains anything clickable. Containers that are
     * only clickable to swallow taps (cards, scrims) have clickable children or are
     * large, and are left alone; so are the canvas, web views and text fields.
     */
    private boolean sweepHover(View v) {
        if (v == null || v.getVisibility() == View.GONE) return false;
        if (v instanceof CodeCanvasView || v instanceof WebView || v instanceof EditText) {
            return v.isClickable();
        }
        boolean childClickable = false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                childClickable |= sweepHover(g.getChildAt(i));
            }
        }
        boolean clickable = v.isClickable() || v.isLongClickable();
        if (!clickable || childClickable || Build.VERSION.SDK_INT < 23) {
            return clickable || childClickable;
        }
        addPressMotion(v);
        if (v.getForeground() != null || hoverDecorated.contains(v)) return true;
        if (v.getBackground() instanceof android.graphics.drawable.RippleDrawable) return true;
        int w = v.getWidth();
        int h = v.getHeight();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (w <= 0 || h <= 0 || w > dm.widthPixels * 0.6f || h > dm.heightPixels * 0.5f) {
            return true;
        }
        boolean oval = false;
        float radius = dp(12);
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) bg;
            oval = gd.getShape() == GradientDrawable.OVAL;
            float r = gd.getCornerRadius();
            if (r > 0) radius = Math.min(r, Math.min(w, h) / 2f);
        } else if (Math.abs(w - h) < dp(6) && w <= dp(56)) {
            oval = true;
        }
        GradientDrawable mask = new GradientDrawable();
        if (oval) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(radius);
        mask.setColor(0xFFFFFFFF);
        v.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(hoverTint()), null, mask));
        hoverDecorated.add(v);
        return true;
    }

    /** Overlay windows that animated open, so removing them animates them closed. */
    final java.util.Set<View> openedWindows =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Opening animation for overlay windows, the same motion as the undo scrubber's
     * card: a full-screen, clickable frame with a translucent scrim fades in; its
     * first child (the card) lands — scaling up from 88% with a slight overshoot while
     * rising into place. Persistent panels and transparent dropdown catchers are left
     * alone — dropdowns run their own slide-in.
     */
    private void animateWindowOpen(View child) {
        if (!(child instanceof FrameLayout) || !child.isClickable()) return;
        if (child == centerPane || child == chatPanel || child == explorerPanel
                || child == editorPanel || child == allProjectsView) {
            return;
        }
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            return;
        }
        android.graphics.drawable.Drawable bg = child.getBackground();
        if (!(bg instanceof android.graphics.drawable.ColorDrawable)
                || ((android.graphics.drawable.ColorDrawable) bg).getAlpha() == 0) {
            return;
        }
        openedWindows.add(child);
        child.animate().cancel();
        child.setAlpha(0f);
        child.animate().alpha(1f).setDuration(200)
                .setInterpolator(Motion.STANDARD).start();
        if (((ViewGroup) child).getChildCount() > 0) {
            View card = ((ViewGroup) child).getChildAt(0);
            card.setAlpha(0f);
            // Grow from the card's own centre once it has a size.
            card.post(() -> Motion.popIn(card, card.getWidth() / 2f,
                    card.getHeight() / 2f, 16f));
        }
    }

    /** Scrim fades while the card drops away; then the window is really gone. */
    private void animateWindowClose(ViewGroup parent, View window) {
        Motion.fadeOut(window, parent);
        View card = window instanceof ViewGroup && ((ViewGroup) window).getChildCount() > 0
                ? ((ViewGroup) window).getChildAt(0) : null;
        Runnable done = () -> {
            parent.endViewTransition(window);
            window.setAlpha(1f);
            if (card != null) {
                card.setAlpha(1f);
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setTranslationY(0f);
            }
            parent.invalidate();
        };
        if (card != null) Motion.popOut(card, 8f, parent, done);
        else window.postOnAnimationDelayed(done, Motion.EXIT_MS);
    }

    /** Idle tint for every plain icon button directly inside {@code group}. */
    private void retintIdleIcons(ViewGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c == presentHideButton) continue;
            if (c instanceof ImageView && c.isClickable()) applyIconSelected((ImageView) c, false);
            else if (c instanceof ViewGroup && !(c instanceof android.widget.AdapterView)) {
                retintIdleIcons((ViewGroup) c);
            }
        }
    }

    void applyFilterChip(ImageView v, boolean on) {
        if (v == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (on) {
            bg.setColor(M3_PRIMARY_CONTAINER);
            v.setColorFilter(new PorterDuffColorFilter(M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        } else {
            bg.setColor(0x00000000);
            v.setColorFilter(new PorterDuffColorFilter(M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        }
        v.setBackground(withHoverRipple(bg, true));
        if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
    }

    View roundSwatch(int fill, boolean selected) {
        View v = new View(this);
        v.setClickable(true);
        v.setFocusable(true);
        v.setSoundEffectsEnabled(true);
        applyRoundStyle(v, fill, selected);
        return v;
    }

    void applyRoundStyle(View v, int fill, boolean selected) {
        GradientDrawable fillDot = new GradientDrawable();
        fillDot.setShape(GradientDrawable.OVAL);
        fillDot.setColor(fill);
        if (!selected) {
            v.setBackground(fillDot);
            if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
            return;
        }
        // Selected: small contrasting circle in the middle of the swatch (not an outer ring).
        int r = (fill >> 16) & 0xFF;
        int g = (fill >> 8) & 0xFF;
        int b = fill & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        GradientDrawable mark = new GradientDrawable();
        mark.setShape(GradientDrawable.OVAL);
        mark.setColor(luminance > 140 ? 0xE61A1A1A : 0xE6F5F5F5);
        LayerDrawable d = new LayerDrawable(new Drawable[]{fillDot, mark});
        int inset = dp(6);
        d.setLayerInset(1, inset, inset, inset, inset);
        v.setBackground(d);
        if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
    }

    PdfTextSelectionView pdfTextSelection;

    private void applyPdfSelectionColors() {
        if (pdfTextSelection == null) return;
        pdfTextSelection.applyColors(
                (M3_PRIMARY & 0x00FFFFFF) | 0x55000000,
                M3_PRIMARY,
                M3_SURFACE_CONTAINER_HIGHEST,
                M3_ON_SURFACE);
    }

    void hideSoftKeyboard() {
        View focus = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            View token = focus != null ? focus : (canvas != null ? canvas : chatInput);
            if (token != null) {
                imm.hideSoftInputFromWindow(token.getWindowToken(), 0);
            }
        }
    }

    void showSoftKeyboard(View target) {
        if (target == null) return;
        target.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ctrl = event.isCtrlPressed()
                || (event.getMetaState() & KeyEvent.META_META_ON) != 0;
        // The pen's own buttons arrive as PAGE_DOWN / PAGE_UP. A remapper used to
        // rewrite them to Ctrl+4 / Ctrl+5, and only those were handled — so whenever
        // the remap was not running, or the system claimed that combination for
        // itself, both pen roles went dead at once: no eraser, no favourites radial.
        // Take the pen's keys directly as well, and keep the remapped pair working.
        if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_PAGE_UP) {
            boolean penA = code == KeyEvent.KEYCODE_PAGE_DOWN;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (penA) penKeyAHeld = true;
                else penKeyBHeld = true;
                penTools.onPenKeyEdge(penA, true);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (penA) penKeyAHeld = false;
                else penKeyBHeld = false;
                penTools.onPenKeyEdge(penA, false);
                return true;
            }
            return true;
        }
        // pen_remap → Inkside: PAGEDOWN=Ctrl+4 hold, PAGEUP=Ctrl+5 hold.
        if (code == KeyEvent.KEYCODE_4 || code == KeyEvent.KEYCODE_5) {
            boolean penA = code == KeyEvent.KEYCODE_4;
            boolean held = penA ? penKeyAHeld : penKeyBHeld;
            if (ctrl || held) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (penA) penKeyAHeld = true;
                    else penKeyBHeld = true;
                    penTools.onPenKeyEdge(penA, true);
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (penA) penKeyAHeld = false;
                    else penKeyBHeld = false;
                    penTools.onPenKeyEdge(penA, false);
                    return true;
                }
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && ctrl) {
            if (code == KeyEvent.KEYCODE_1) {
                hideSoftKeyboard();
                penTools.selectPencil(selectedColorIndex);
                return true;
            }
            if (code == KeyEvent.KEYCODE_6) {
                hideSoftKeyboard();
                penTools.selectLasso();
                return true;
            }
            if (code == KeyEvent.KEYCODE_L) {
                hideSoftKeyboard();
                if (canvas != null) {
                    canvas.requestFocus();
                    canvas.requestImagePasteArm();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_Z) {
                hideSoftKeyboard();
                if (event.isShiftPressed()) {
                    if (canvas != null) canvas.redo();
                } else {
                    if (canvas != null) canvas.undo();
                }
                persistence.scheduleSave();
                return true;
            }
            if (code == KeyEvent.KEYCODE_Y) {
                hideSoftKeyboard();
                if (canvas != null) canvas.redo();
                persistence.scheduleSave();
                return true;
            }
            if (code == KeyEvent.KEYCODE_X && canvas != null && canvas.hasActiveSelection()
                    && !conversations.chatInputHasFocus()) {
                if (canvas.cutSelection()) persistence.scheduleSave();
                return true;
            }
            if (code == KeyEvent.KEYCODE_C && canvas != null && canvas.hasActiveSelection()
                    && !conversations.chatInputHasFocus()) {
                canvas.copySelectionToClipboard();
                return true;
            }
            if (code == KeyEvent.KEYCODE_V && canvas != null && canvas.hasCanvasClipboard()
                    && !conversations.chatInputHasFocus() && canvasPaste.loadClipboardBitmap(true) == null) {
                hideSoftKeyboard();
                float[] c = canvas.getViewCenterWorld();
                if (canvas.pasteClipboardAt(c[0], c[1])) persistence.scheduleSave();
                return true;
            }
            if (code == KeyEvent.KEYCODE_V) {
                hideSoftKeyboard();
                if (canvasPaste.tryPasteImageToCanvas()) return true;
                // Text pasted into the document lands as a text box at the view centre.
                if (!conversations.chatInputHasFocus() && canvas != null && canvas.hasDocument()) {
                    float[] c = canvas.getViewCenterWorld();
                    if (canvasPaste.tryPasteTextAt(c[0], c[1])) {
                        persistence.scheduleSave();
                        return true;
                    }
                }
                conversations.ingestClipboardAttachments();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Pages: reorder, duplicate, insert, delete -------------------------------

    /** A filled, rounded action for the page and search panels. */
    TextView panelAction(String label, boolean primary, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(primary ? M3_ON_PRIMARY_CONTAINER : M3_PRIMARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(SPACE_XL + 2), dp(SPACE_MD + 1), dp(SPACE_XL + 2), dp(SPACE_MD + 1));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        if (primary) {
            bg.setColor(M3_PRIMARY_CONTAINER);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1), M3_OUTLINE_VARIANT);
        }
        t.setBackground(bg);
        t.setOnClickListener(v -> {
            if (v.isEnabled()) onClick.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(SPACE_MD), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(lp);
        return t;
    }

    static void setActionEnabled(TextView t, boolean enabled) {
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.38f);
    }

    /** A dimmed full-screen layer holding a large card; returns {overlay, card}. */
    View[] buildPanelShell(float widthFrac, float heightFrac, Runnable onOutsideTap) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(SPACE_XL + 4), dp(SPACE_XL + 2), dp(SPACE_XL + 4), dp(SPACE_XL));
        settingsPanel.applyOptionsCardSurface(card);
        card.setElevation(dp(6));
        card.setClickable(true);
        int w = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = rootLayout.getHeight() > 0 ? rootLayout.getHeight() : getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(dp(1100), Math.round(w * widthFrac)), Math.round(h * heightFrac));
        lp.gravity = Gravity.CENTER;
        overlay.addView(card, lp);
        overlay.setOnClickListener(v -> onOutsideTap.run());
        return new View[] {overlay, card};
    }

    /** Dialog / panel title: M3 headline small, as in Settings, Export and Page style. */
    TextView panelTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(M3_ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        return title;
    }

    TextView panelHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(M3_ON_SURFACE_VARIANT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return hint;
    }

    /** True once the Activity is gone; async callbacks must not touch views past this. */
    boolean isDead() {
        return isFinishing() || isDestroyed();
    }

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
