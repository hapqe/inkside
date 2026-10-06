package me.hapke.inkside;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.webkit.WebView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat sessions: tabs, sending, attachments, streaming replies, resuming runs and the chat log.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Conversations {
    private final MainActivity act;

    /**
     * Per-chat stream generations. Starting/stopping chat A must not silence chat B's
     * callbacks (bridge already supports concurrent agents per chatId).
     */
    private final java.util.HashMap<String, Integer> chatStreamGenById = new java.util.HashMap<>();
    /** Chats with an in-flight agent run. */
    private final java.util.LinkedHashSet<String> streamingChatIds = new java.util.LinkedHashSet<>();
    /** Last activity timestamp per streaming chat — used by the busy watchdog. */
    private final java.util.HashMap<String, Long> chatStreamLastBumpMs = new java.util.HashMap<>();

    private final Runnable chatBusyWatchdog = this::onChatBusyWatchdogTick;

    Conversations(MainActivity act) {
        this.act = act;
    }

    void onSessionCheckpoint() {
        if (!act.sessionDirty || act.restoring) return;
        act.persistence.saveSessionNow();
    }

    private void onChatBusyWatchdogTick() {
        if (streamingChatIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.ArrayList<String> timedOut = new java.util.ArrayList<>();
        for (String id : new java.util.ArrayList<>(streamingChatIds)) {
            Long last = chatStreamLastBumpMs.get(id);
            long age = last != null ? now - last : 0L;
            if (age < 180_000L) continue;
            timedOut.add(id);
        }
        for (String id : timedOut) {
            // Silence on the pipe is not a dead agent any more: the run belongs to the
            // bridge. Ask before writing the session off.
            recheckSession(id);
        }
        if (!streamingChatIds.isEmpty()) {
            act.saveHandler.postDelayed(chatBusyWatchdog, 15_000L);
        }
    }

    /** Retained transcript per chat; see {@link #trimChatLog}. */
    private static final int MAX_CHAT_LOG_CHARS = 250_000;

    ChatSession activeChat() {
        if (act.activeChatId == null) return null;
        for (ChatSession c : act.chats) {
            if (act.activeChatId.equals(c.id)) return c;
        }
        return null;
    }

    /**
     * Chat files another device wrote (names under chats/, from sync): new chats are added, a
     * known one takes the newer version, a deleted one goes. A chat streaming here is left alone.
     */
    void adoptChatFiles(java.util.List<String> pulled, java.util.List<String> deleted) {
        boolean changed = false;
        for (String name : pulled) {
            try {
                java.io.File f = new java.io.File(act.chatsDir, name);
                JSONObject o = new JSONObject(new String(LocalWorkspace.readAll(f), java.nio.charset.StandardCharsets.UTF_8));
                String id = o.optString("id", "");
                if (id.isEmpty() || streamingChatIds.contains(id)) continue;
                ChatSession c = findChat(id);
                long updated = o.optLong("updatedAt", 0);
                if (c == null) {
                    c = new ChatSession(id, o.optString("title", "Chat"));
                    act.chats.add(c);
                } else if (updated <= c.updatedAt) {
                    continue;
                }
                c.title = o.optString("title", c.title);
                c.log.setLength(0);
                c.log.append(o.optString("log", ""));
                c.updatedAt = updated > 0 ? updated : c.updatedAt;
                c.projectPath = o.optString("projectPath", "");
                act.persistence.chatAdopted(c);
                changed = true;
                if (id.equals(act.activeChatId)) loadChatIntoUi(c);
            } catch (Exception ignored) {
                // An unreadable chat file is skipped; the next sync brings it again.
            }
        }
        for (String name : deleted) {
            String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            ChatSession c = findChat(id);
            if (c != null && !streamingChatIds.contains(id) && !id.equals(act.activeChatId)) {
                act.chats.remove(c);
                changed = true;
            }
        }
        if (changed) {
            refreshChatTabs();
            act.persistence.scheduleSave();
        }
    }

    ChatSession findChat(String id) {
        if (id == null) return null;
        for (ChatSession c : act.chats) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    void ensureDefaultChat() {
        String active = act.activeProjectPath != null ? act.activeProjectPath : "";
        // Prefer a normal workspace chat for the active project.
        ChatSession workspace = null;
        for (ChatSession c : act.chats) {
            String p = c.projectPath != null ? c.projectPath : "";
            if (p.equals(active) || (active.isEmpty() && p.isEmpty())) {
                workspace = c;
                break;
            }
        }
        if (workspace == null) {
            workspace = new ChatSession(newChatId(), "Chat");
            workspace.projectPath = active;
            act.chats.add(workspace);
        }
        if (act.activeChatId == null || findChat(act.activeChatId) == null) {
            act.activeChatId = workspace.id;
        }
    }

    static String newChatId() {
        return "c" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    void syncActiveFromUi() {
        ChatSession cur = activeChat();
        if (cur == null) return;
        cur.log.setLength(0);
        cur.log.append(act.chatPlain);
        if (act.chatInput != null) cur.draft = act.chatInput.getText().toString();
        cur.updatedAt = System.currentTimeMillis();
    }

    private void loadChatIntoUi(ChatSession c) {
        if (c == null) return;
        act.activeChatId = c.id;
        act.chatPlain.setLength(0);
        act.chatPlain.append(c.log);
        if (act.chatInput != null) {
            act.chatInput.setText(c.draft != null ? c.draft : "");
            act.chatInput.setHint(act.chatIdleHint());
        }
        evalChatJs("setAll(" + JSONObject.quote(act.chatPlain.toString()) + ")");
        if (act.miniChat != null) act.miniChat.setTitle(c.title);
        refreshChatTabs();
        syncAgentActivity();
        syncChatRunState();
        refreshChatComposer();
        resumeRunningSessions();
    }

    void switchChat(String id) {
        if (id == null || id.equals(act.activeChatId)) return;
        ChatSession next = findChat(id);
        if (next == null) return;
        syncActiveFromUi();
        loadChatIntoUi(next);
        act.persistence.scheduleSave();
    }

    /** No message in it yet (and nothing running): what "New chat" would make anyway. */
    private boolean isEmptyChat(ChatSession c) {
        return c != null && c.log.toString().trim().isEmpty() && !isChatStreaming(c.id);
    }

    /**
     * Drops the active project's surplus empty chats (no messages, no draft), keeping one:
     * the open chat if it is empty, else the newest. Earlier builds let them pile up.
     */
    private void pruneExtraEmptyChats() {
        ChatSession keep = null;
        ChatSession active = findChat(act.activeChatId);
        if (chatBelongsToActiveProject(active) && isEmptyChat(active)) keep = active;
        java.util.List<ChatSession> extra = new java.util.ArrayList<>();
        for (ChatSession c : act.chats) {
            if (!chatBelongsToActiveProject(c) || !isEmptyChat(c)) continue;
            if (c.draft != null && !c.draft.trim().isEmpty()) continue;
            if (keep == null) keep = c;
            else if (c != keep) extra.add(c);
        }
        if (extra.isEmpty()) return;
        act.chats.removeAll(extra);
        refreshChatTabs();
        act.persistence.scheduleSave();
    }

    /**
     * A new chat in the active project — unless the project already has an empty one,
     * which is opened instead: one empty chat per project is enough.
     */
    void createNewChat() {
        syncActiveFromUi();
        pruneExtraEmptyChats();
        for (ChatSession existing : act.chats) {
            if (!chatBelongsToActiveProject(existing) || !isEmptyChat(existing)) continue;
            if (!existing.id.equals(act.activeChatId)) {
                loadChatIntoUi(existing);
                act.persistence.scheduleSave();
            }
            if (act.chatInput != null) act.chatInput.requestFocus();
            return;
        }
        ChatSession c = new ChatSession(newChatId(), "New chat");
        c.projectPath = act.activeProjectPath != null ? act.activeProjectPath : "";
        act.chats.add(0, c);
        loadChatIntoUi(c);
        act.persistence.scheduleSave();
    }

    private void deleteChat(String id) {
        if (isChatStreaming(id)) {
            stopChatForId(id);
        }
        // Count this project's chats; always keep at least one.
        int workspaceCount = 0;
        String activeProj = act.activeProjectPath != null ? act.activeProjectPath : "";
        for (ChatSession c : act.chats) {
            if (activeProj.equals(c.projectPath != null ? c.projectPath : "")) {
                workspaceCount++;
            }
        }
        if (workspaceCount <= 1) {
            ChatSession only = null;
            for (ChatSession c : act.chats) {
                if (activeProj.equals(c.projectPath != null ? c.projectPath : "")) {
                    only = c;
                    break;
                }
            }
            if (only == null) {
                only = new ChatSession(newChatId(), "Chat");
                only.projectPath = activeProj;
                act.chats.add(only);
            }
            only.log.setLength(0);
            only.draft = "";
            only.title = "Chat";
            only.updatedAt = System.currentTimeMillis();
            // Drop the agent session so the cleared UI starts a new conversation.
            act.bridge.resetAgent(only.id, new BridgeClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean v) { /* ok */ }
                @Override public void onError(String e) { /* ignore */ }
            });
            loadChatIntoUi(only);
            act.persistence.scheduleSave();
            return;
        }
        ChatSession victim = findChat(id);
        if (victim == null) return;
        boolean wasActive = id.equals(act.activeChatId);
        act.chats.remove(victim);
        act.bridge.resetAgent(id, new BridgeClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean v) { /* ok */ }
            @Override public void onError(String e) { /* ignore */ }
        });
        if (wasActive) {
            ChatSession next = null;
            for (ChatSession c : act.chats) {
                if (activeProj.equals(c.projectPath != null ? c.projectPath : "")) {
                    next = c;
                    break;
                }
            }
            loadChatIntoUi(next != null ? next : act.chats.get(0));
        } else {
            refreshChatTabs();
        }
        act.persistence.scheduleSave();
    }

    private void maybeUpdateChatTitle(String userMessage) {
        ChatSession cur = activeChat();
        if (cur == null) return;
        if (!"Chat".equals(cur.title) && !"New chat".equals(cur.title)) return;
        String t = userMessage == null ? "" : userMessage.trim().replace('\n', ' ');
        if (t.isEmpty()) return;
        if (t.length() > 28) t = t.substring(0, 27) + "…";
        cur.title = t;
        refreshChatTabs();
    }

    void refreshChatTabs() {
        if (act.chatTabsRow == null) return;
        act.chatTabsRow.removeAllViews();
        act.chatView.updateChatDragHeaderTitle();

        for (int i = 0; i < act.chats.size(); i++) {
            final ChatSession session = act.chats.get(i);
            if (!chatBelongsToActiveProject(session)) continue;
            boolean active = session.id.equals(act.activeChatId);
            boolean streaming = isChatStreaming(session.id);

            LinearLayout tab = new LinearLayout(act);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD));
            tab.setMinimumHeight(act.dp(44));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setSoundEffectsEnabled(true);
            GradientDrawable tabBg = new GradientDrawable();
            tabBg.setCornerRadius(act.dp(10));
            if (streaming) {
                tabBg.setColor(act.M3_PRIMARY_CONTAINER);
            } else if (active) {
                tabBg.setColor(act.M3_SURFACE_CONTAINER);
            } else {
                tabBg.setColor(0x00000000);
            }
            tab.setBackground(tabBg);

            if (streaming) {
                ProgressBar spin = new ProgressBar(act);
                spin.setIndeterminate(true);
                if (Build.VERSION.SDK_INT >= 21) {
                    spin.getIndeterminateDrawable().setColorFilter(
                            new PorterDuffColorFilter(act.M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
                }
                LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(act.dp(14), act.dp(14));
                spinLp.rightMargin = act.dp(MainActivity.SPACE_SM);
                tab.addView(spin, spinLp);
            }

            TextView label = new TextView(act);
            label.setText(session.title);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(Typeface.create(
                    active || streaming ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            label.setTextColor(streaming
                    ? act.M3_ON_PRIMARY_CONTAINER
                    : (active ? act.M3_ON_SURFACE : act.M3_ON_SURFACE_VARIANT));
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tabLp.bottomMargin = act.dp(MainActivity.SPACE_SM);
            tab.setOnClickListener(v -> {
                switchChat(session.id);
                act.chatView.hideChatTabsOverlay();
            });
            tab.setOnLongClickListener(v -> {
                new M3Dialog.Builder(act)
                        .setMessage("Delete “" + session.title + "”?")
                        .setPositiveButton("Delete", (d, w) -> deleteChat(session.id))
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            act.chatTabsRow.addView(tab, tabLp);
        }
        act.chatView.refreshChatRunBanner();
    }

    /**
     * Any number of files of any type. Opened straight in the system file picker:
     * wrapped in a chooser, apps like Photos could take over and allow only one.
     */
    void openAttachmentPicker(String role, boolean documentsOnly) {
        act.pendingAttachRole = role == null ? "" : role;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (documentsOnly) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/pdf", "text/*",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"});
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            act.startActivityForResult(intent, MainActivity.REQ_PICK_ATTACHMENT);
        } catch (Exception e) {
            act.statusToast("No file picker available");
        }
    }

    /** One picked file as an attachment; any type. Null when it is empty. */
    PendingAttachment readAttachment(Uri uri, String mimeType, String name) throws Exception {
        byte[] bytes;
        try (InputStream in = act.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot read it");
            bytes = readAll(in, 20_000_000);
        }
        if (bytes == null || bytes.length == 0) return null;
        boolean image = mimeType != null && mimeType.startsWith("image/");
        if (name == null || name.isEmpty()) name = image ? "image.png" : "attachment.bin";
        return new PendingAttachment(name, mimeType, bytes, image);
    }

    /** What the tutor is asked when checking work: find the mistakes, keep the solving to me. */
    private static final String CHECK_WORK_PROMPT =
            "Check my work in the attached picture. Point out each mistake and where my "
                    + "reasoning goes wrong, and give me a hint for each so I can fix it myself "
                    + "- don't give me the full solution. If everything is right, say so briefly.";

    /**
     * Sends the selection, or else the whole current page, to the tutor to be checked.
     * The page is rendered whole, not just the part on screen.
     */
    void checkMyWork() {
        if (act.canvas == null || !act.canvas.hasDocument()) {
            act.snackbar("Open a document first", false);
            return;
        }
        if (!act.aiEnabled || !act.computers.hasHost()) {
            act.snackbar("Checking work needs a connected computer", false);
            return;
        }
        if (act.canvas.hasActiveSelection() || act.canvas.hasLassoRegion()) {
            android.graphics.RectF bounds = act.canvas.getCaptureBoundsWorld();
            byte[] png = bounds != null ? act.canvas.captureRegionPng(bounds, 1600) : null;
            sendForChecking(png);
            return;
        }
        int page = Math.max(0, act.canvas.currentPageIndex());
        act.snackbar("Checking page " + (page + 1) + "\u2026", false);
        act.canvas.renderPage(page, 1600, 2200, true, bmp -> act.runOnUiThread(() -> {
            if (bmp == null) {
                sendForChecking(null);
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, bos);
            bmp.recycle();
            sendForChecking(bos.toByteArray());
        }));
    }

    private void sendForChecking(byte[] png) {
        if (png == null || png.length == 0) {
            act.snackbar("Could not capture the page", false);
            return;
        }
        act.pendingAttachments.add(new PendingAttachment("check-my-work.png", "image/png", png, true));
        refreshAttachRow();
        if (act.zenMode) act.zen.exitZenMode();
        if (act.chatCollapsed) act.chatView.animateChatToExpanded(act.chatView.chatPanelWidth());
        sendTextViaChat(CHECK_WORK_PROMPT);
    }

    void addSelectionScreenshotToChat() {
        if (act.canvas == null) return;
        android.graphics.RectF bounds = act.canvas.getCaptureBoundsWorld();
        if (bounds == null) return;
        byte[] png = act.canvas.captureRegionPng(bounds, 1600);
        if (png == null || png.length == 0) {
            appendChat("warn", "screenshot failed");
            return;
        }
        act.pendingAttachments.add(new PendingAttachment("selection.png", "image/png", png, true));
        refreshAttachRow();
        if (act.chatCollapsed) act.chatView.toggleChatCollapsed();
        appendChat("warn", "selection screenshot added — send when ready");
    }

    boolean chatBelongsToActiveProject(ChatSession c) {
        if (c == null) return false;
        String p = c.projectPath != null ? c.projectPath : "";
        String active = act.activeProjectPath != null ? act.activeProjectPath : "";
        return p.equals(active);
    }

    void ensureChatsForActiveProject() {
        String active = act.activeProjectPath != null ? act.activeProjectPath : "";
        ChatSession workspace = null;
        for (ChatSession c : act.chats) {
            if (active.equals(c.projectPath != null ? c.projectPath : "")) {
                workspace = c;
                break;
            }
        }
        if (workspace == null && !active.isEmpty()) {
            workspace = new ChatSession(newChatId(), "Chat");
            workspace.projectPath = active;
            act.chats.add(workspace);
        }
        if (workspace != null) {
            if (act.activeChatId == null || findChat(act.activeChatId) == null
                    || !chatBelongsToActiveProject(findChat(act.activeChatId))) {
                loadChatIntoUi(workspace);
            } else {
                refreshChatTabs();
            }
        } else {
            refreshChatTabs();
        }
        pruneExtraEmptyChats();
    }

    /**
     * Sends {@code msg} through the normal chat without disturbing a draft that sits
     * in its input. If the chat cannot take it now, it is left there as the draft.
     */
    void sendTextViaChat(String msg) {
        if (act.chatInput == null || msg == null || msg.trim().isEmpty()) return;
        String keep = act.chatInput.getText() != null ? act.chatInput.getText().toString() : "";
        if (isActiveChatStreaming()) {
            act.chatInput.setText(keep.isEmpty() ? msg : keep + " " + msg);
            act.snackbar("Dictation kept in the chat input", false);
            return;
        }
        act.chatInput.setText(msg.trim());
        sendChat();
        act.chatInput.setText(keep);
    }

    /** Quote selected PDF text into the message box and bring the chat up. */
    void addPdfQuoteToChat(String text) {
        if (act.chatInput == null || text == null || text.trim().isEmpty()) return;
        StringBuilder quote = new StringBuilder();
        for (String line : text.trim().split("\n")) quote.append("> ").append(line).append('\n');
        android.text.Editable ed = act.chatInput.getText();
        String prefix = ed.length() > 0 && ed.charAt(ed.length() - 1) != '\n' ? "\n" : "";
        ed.append(prefix).append(quote).append('\n');
        act.chatInput.setSelection(ed.length());
        if (act.zenMode) act.zen.exitZenMode();
        if (act.chatCollapsed) act.chatView.animateChatToExpanded(act.chatView.chatPanelWidth());
    }

    /** A text field or web view owns the keyboard — leave Ctrl+X/C/V to it. */
    boolean chatInputHasFocus() {
        View f = act.getCurrentFocus();
        return f instanceof android.widget.EditText || f instanceof WebView
                || (f instanceof TextView && ((TextView) f).isTextSelectable());
    }

    void ingestClipboardAttachments() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null) ingestClipData(clip);
    }

    private void ingestClipData(ClipData clip) {
        if (clip == null) return;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri != null) {
                addAttachmentFromUri(uri, guessMime(clip, i, uri));
                continue;
            }
            // Some apps put a content URI only in the Intent.
            if (item.getIntent() != null && item.getIntent().getData() != null) {
                Uri intentUri = item.getIntent().getData();
                addAttachmentFromUri(intentUri, guessMime(clip, i, intentUri));
            }
        }
        refreshAttachRow();
    }

    String guessMime(ClipData clip, int index, Uri uri) {
        String mime = null;
        if (clip.getDescription() != null && index < clip.getDescription().getMimeTypeCount()) {
            mime = clip.getDescription().getMimeType(index);
        }
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)
                || "*/*".equals(mime) || mime.startsWith("text/uri")) {
            String resolved = act.getContentResolver().getType(uri);
            if (resolved != null) mime = resolved;
        }
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        return mime;
    }

    void addAttachmentFromUri(Uri uri, String mimeType) {
        try {
            ContentResolver cr = act.getContentResolver();
            String name = queryDisplayName(uri);
            byte[] data;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return;
                data = readAll(in, 20_000_000);
            }
            if (data == null || data.length == 0) return;
            boolean image = mimeType != null && mimeType.startsWith("image/");
            if (name == null || name.isEmpty()) {
                name = image ? "image.png" : "document.bin";
            }
            // Avoid duplicates of the exact same bytes+name in one compose.
            for (PendingAttachment existing : act.pendingAttachments) {
                if (existing.name.equals(name) && existing.data.length == data.length) return;
            }
            act.pendingAttachments.add(new PendingAttachment(name, mimeType, data, image));
        } catch (Exception e) {
            appendChat("warn", "paste failed: " + e.getMessage());
        }
    }

    String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : null;
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) throw new Exception("over " + (max / 1_000_000) + " MB");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    void refreshAttachRow() {
        if (act.attachRow == null) return;
        act.attachRow.removeAllViews();
        if (act.attachScroll != null) {
            act.attachScroll.setVisibility(act.pendingAttachments.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (int i = 0; i < act.pendingAttachments.size(); i++) {
            final int index = i;
            PendingAttachment a = act.pendingAttachments.get(i);
            // Material 3 input chip: kind icon, name, close icon; 8dp corners.
            TextView chip = new TextView(act);
            chip.setText(a.role.equals("reference") ? "Reference · " + a.name
                    : a.role.equals("goal") ? "Goal · " + a.name : a.name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setSingleLine(true);
            chip.setMaxWidth(act.dp(220));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setMinHeight(act.dp(32));
            chip.setPadding(act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_SM), 0);
            chip.setContentDescription("Remove " + a.name);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(act.dp(8));
            int fg;
            if (a.script) {
                bg.setColor(act.M3_PRIMARY_CONTAINER);
                fg = act.M3_ON_PRIMARY_CONTAINER;
            } else if (a.image) {
                bg.setColor(act.M3_SECONDARY_CONTAINER);
                fg = act.M3_ON_SURFACE;
            } else {
                bg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
                fg = act.M3_ON_SURFACE;
            }
            chip.setTextColor(fg);
            android.graphics.drawable.Drawable kind = act.getDrawable(a.script ? R.drawable.ic_script
                    : a.image ? R.drawable.ic_image
                    : a.workspacePath != null ? R.drawable.ic_file : R.drawable.ic_pdf).mutate();
            kind.setTint(fg);
            kind.setBounds(0, 0, act.dp(18), act.dp(18));
            android.graphics.drawable.Drawable x = act.getDrawable(R.drawable.ic_close).mutate();
            x.setTint(fg);
            x.setBounds(0, 0, act.dp(16), act.dp(16));
            chip.setCompoundDrawablesRelative(kind, null, x, null);
            chip.setCompoundDrawablePadding(act.dp(MainActivity.SPACE_SM));
            chip.setBackground(act.withHoverRipple(bg, false));
            chip.setOnClickListener(v -> {
                act.pendingAttachments.remove(index);
                refreshAttachRow();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM));
            act.attachRow.addView(chip, lp);
        }
    }

    void sendChat() {
        // Send pressed mid-dictation: stop, wait for the transcript, then send it.
        if (act.voiceRecorder != null) {
            act.sendAfterDictation = true;
            act.dictation.stopVoiceRecording(true);
            return;
        }
        if (act.voiceTranscribing) {
            act.sendAfterDictation = true;
            return;
        }
        if (isActiveChatStreaming()) return;
        String msg = act.chatInput.getText().toString().trim();
        if (msg.isEmpty() && act.pendingAttachments.isEmpty()) return;

        // Bridge already runs agents per chatId — do not cancel other workspace chats.

        String label = msg;
        if (!act.pendingAttachments.isEmpty()) {
            StringBuilder sb = new StringBuilder(msg);
            if (sb.length() > 0) sb.append(' ');
            sb.append('[').append(act.pendingAttachments.size()).append(" attachment(s)]");
            label = sb.toString();
        }
        appendChat("you", label);
        act.chatInput.setText("");

        JSONArray images = new JSONArray();
        JSONArray documents = new JSONArray();
        try {
            for (PendingAttachment a : act.pendingAttachments) {
                if (a.workspacePath != null && a.data == null) continue;
                if (a.data == null) continue;
                JSONObject o = new JSONObject();
                o.put("name", a.name);
                o.put("mimeType", a.mimeType);
                if (!a.role.isEmpty()) o.put("role", a.role);
                o.put("data", Base64.encodeToString(a.data, Base64.NO_WRAP));
                if (a.image) images.put(o);
                else documents.put(o);
            }
        } catch (Exception e) {
            appendChat("error", e.getMessage());
            return;
        }
        final String sendMsg = msg;
        act.pendingAttachments.clear();
        refreshAttachRow();

        final String chatId = act.activeChatId;
        markChatStreaming(chatId);
        appendChat("agent", "…");

        try {
            // Code lives in the editor panel now — tell the agent which file is open there.
            final String openPath = act.scriptEditorPath != null && !act.scriptEditorPath.isEmpty()
                    ? act.scriptEditorPath
                    : (act.openFile != null ? act.openFile : null);
            maybeUpdateChatTitle(sendMsg);
            // The agent works on the computer's copy: bring it up to date first.
            final String fChatId = chatId;
            final String fOpenPath = openPath;
            act.syncThen(() -> dispatchChatStream(fChatId, sendMsg, fOpenPath, images, documents, 0, false));
        } catch (Exception e) {
            clearStreaming(chatId);
            appendChat("error", e.getMessage());
        }
    }

    private boolean shouldRetryChat(BridgeClient.ChatResult value) {
        if (value == null || "cancelled".equals(value.status)) return false;
        if ("error".equals(value.status)) return true;
        String text = value.text;
        return text != null && text.contains("no text returned");
    }

    void finishChatResponse(BridgeClient.ChatResult value) {
        // Prefer active chat; callers that know the id use the two-arg overload.
        String id = act.activeChatId;
        for (String s : streamingChatIds) {
            if (s.equals(act.activeChatId)) { id = s; break; }
            id = s;
        }
        finishChatResponse(id, value);
    }

    void finishChatResponse(String chatId, BridgeClient.ChatResult value) {
        hideAgentActivity(chatId);
        // Belt and braces next to the file watcher: the agent may have rebuilt the PDF.
        // What the agent changed on the computer comes back before the open PDF is re-read.
        act.syncThen(act.documents::reloadOpenPdfIfChanged);
        String out = value.text;
        if ((out == null || out.trim().isEmpty()) && value.error != null && !value.error.isEmpty()) {
            out = "Agent error: " + value.error;
        }
        if (out == null || out.trim().isEmpty()) {
            out = "(" + value.status + ")";
        }
        // Never rewrite earlier agent/tool rows with the full cumulative reply.
        // Live streaming already filled each segment; just KaTeX-finalize the tail.
        if (lastChatEntryIsAgent(chatId)) {
            String current = peekLastAgentText(chatId);
            String finalize = (current != null && !current.isEmpty() && !current.startsWith("…"))
                    ? current
                    : out;
            setLastAgentMessageForChat(chatId, finalize, true);
        } else if (chatLooksPending(chatId)) {
            setLastAgentMessageForChat(chatId, out, true);
        }
        if (chatId != null && chatId.equals(act.activeChatId)) {
            syncActiveFromUi();
        } else {
            ChatSession c = findChat(chatId);
            if (c != null) c.updatedAt = System.currentTimeMillis();
            act.persistence.scheduleSave();
        }
        clearStreaming(chatId);
        if (!"cancelled".equals(value.status)) {
            act.codeEditor.refreshOpenFiles();
            act.vizImages.syncVizImagesFromAgentText(out);
        }
    }

    void refreshChatArtifacts() {
        evalChatJs("refreshArtifactFrames()");
    }

    private void dispatchChatStream(
            String chatId,
            String msg,
            String openPath,
            JSONArray images,
            JSONArray documents,
            int streamId,
            boolean isRetry) {
        if (chatId == null) chatId = act.activeChatId;
        if (!isRetry) streamId = bumpStreamGen(chatId);
        final int sid = streamId;
        final String runChatId = chatId;
        markChatStreaming(runChatId);
        String project = null;
        ChatSession sess = findChat(runChatId);
        if (sess != null) {
            project = sess.projectPath != null && !sess.projectPath.isEmpty()
                    ? sess.projectPath
                    : act.activeProjectPath;
        }
        act.bridge.chatStream(
                runChatId,
                msg,
                openPath,
                images,
                documents,
                null,
                project,
                new BridgeClient.ChatStreamListener() {
                    private boolean gotDelta = false;

                    @Override
                    public void onStatus(String message) {
                        if (!isStreamCurrent(runChatId, sid)) return;
                        if (message == null || message.isEmpty()) return;
                        showAgentActivity(runChatId, message);
                        // Placeholder only until real text or a tool step lands.
                        if (!gotDelta) {
                            setLastAgentMessageForChat(runChatId, "… " + message, false);
                        }
                        bumpChatBusyWatchdog(runChatId);
                    }

                    @Override
                    public void onTool(String name, String detail, String status, String message) {
                        if (!isStreamCurrent(runChatId, sid)) return;
                        String line = message != null && !message.isEmpty()
                                ? message
                                : (name != null ? name : "tool");
                        showAgentActivity(runChatId,
                                status != null && status.equals("running")
                                        ? "using " + line + "…"
                                        : line);
                        appendToolStepForChat(runChatId, line, status);
                        gotDelta = true; // stop overwriting the placeholder with status
                        bumpChatBusyWatchdog(runChatId);
                    }

                    @Override
                    public void onDelta(String text) {
                        if (!isStreamCurrent(runChatId, sid)) return;
                        if (text == null) return;
                        if (turnHasAgent(runChatId)) {
                            setLastAgentMessageForChat(runChatId, text, false);
                        } else {
                            appendToChat(runChatId, "agent", text);
                        }
                        gotDelta = true;
                        bumpChatBusyWatchdog(runChatId);
                    }

                    @Override
                    public void onDone(BridgeClient.ChatResult value) {
                        if (!isStreamCurrent(runChatId, sid)) return;
                        if (!isRetry && shouldRetryChat(value)) {
                            // Keep the same session — bridge resumes by session id.
                            setLastAgentMessageForChat(runChatId, "… retrying agent", false);
                            dispatchChatStream(
                                    runChatId, msg, openPath, images, documents, sid, true);
                            return;
                        }
                        finishChatResponse(runChatId, value);
                    }

                    @Override
                    public void onError(String message) {
                        if (act.isDead()) return;
                        if (!isStreamCurrent(runChatId, sid)) return;
                        if (message != null && message.toLowerCase().contains("active run")) {
                            appendToChat(runChatId, "warn", "clearing stuck run…");
                            act.bridge.cancelChat(runChatId, new BridgeClient.Callback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean value) {
                                    if (act.isDead()) return;
                                    if (!isRetry) {
                                        dispatchChatStream(
                                                runChatId, msg, openPath, images, documents, sid, true);
                                    } else {
                                        clearStreaming(runChatId);
                                        appendToChat(runChatId, "warn", "stuck run cleared — send again");
                                    }
                                }

                                @Override
                                public void onError(String cancelErr) {
                                    if (act.isDead()) return;
                                    clearStreaming(runChatId);
                                    setLastAgentMessageForChat(runChatId, "error: " + message, true);
                                }
                            });
                            return;
                        }
                        clearStreaming(runChatId);
                        setLastAgentMessageForChat(runChatId, "error: " + message, true);
                    }
                });
    }

    boolean isPendingAgentReplyForChat(String chatId) {
        ChatSession c = findChat(chatId);
        String text = c != null
                ? lastAgentMessageTextOf(c.log.toString())
                : null;
        if (text == null && chatId != null && chatId.equals(act.activeChatId)) {
            text = lastAgentMessageText();
        }
        if (text == null) return false;
        text = text.trim();
        if (text.isEmpty()) return false;
        if ("…".equals(text) || text.startsWith("…")) return true;
        if ("running…".equalsIgnoreCase(text) || "running.".equalsIgnoreCase(text)) return true;
        return "running".equalsIgnoreCase(text);
    }

    /** Stops the chat currently on screen, and only that one. */
    void stopChat() {
        String chatId = act.activeChatId;
        if (chatId == null) return;
        if (!isChatStreaming(chatId) && !isPendingAgentReply()) return;
        stopChatForId(chatId);
    }

    private void stopChatForId(String chatId) {
        if (chatId == null) return;
        bumpStreamGen(chatId);
        setLastAgentMessageForChat(chatId, "… stopping", false);
        markChatStreaming(chatId);
        act.bridge.cancelChat(chatId, new BridgeClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                if (act.isDead()) return;
                // Cancel only — the bridge keeps conversation memory for this chatId.
                finishStopChat(chatId, null);
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                finishStopChat(chatId, message);
            }
        });
    }

    private void finishStopChat(String chatId, String resetError) {
        act.saveHandler.postDelayed(() -> {
            if (resetError != null && !resetError.isEmpty()) {
                setLastAgentMessageForChat(chatId, "(stopped — " + resetError + ")", true);
            } else {
                setLastAgentMessageForChat(chatId, "(stopped)", true);
            }
            clearStreaming(chatId);
        }, 400);
    }

    int bumpStreamGen(String chatId) {
        if (chatId == null) chatId = "";
        int next = (chatStreamGenById.containsKey(chatId) ? chatStreamGenById.get(chatId) : 0) + 1;
        chatStreamGenById.put(chatId, next);
        return next;
    }

    boolean isStreamCurrent(String chatId, int sid) {
        Integer cur = chatStreamGenById.get(chatId);
        return cur != null && cur == sid;
    }

    boolean anyChatStreaming() {
        return !streamingChatIds.isEmpty();
    }

    boolean isChatStreaming(String chatId) {
        return chatId != null && streamingChatIds.contains(chatId);
    }

    void markChatStreaming(String chatId) {
        if (chatId == null) return;
        streamingChatIds.add(chatId);
        chatStreamLastBumpMs.put(chatId, System.currentTimeMillis());
        refreshChatComposer();
        refreshChatTabs();
        act.saveHandler.removeCallbacks(chatBusyWatchdog);
        act.saveHandler.postDelayed(chatBusyWatchdog, 15_000L);
    }

    void clearStreaming(String chatId) {
        hideAgentActivity(chatId);
        if (chatId != null) {
            streamingChatIds.remove(chatId);
            chatStreamLastBumpMs.remove(chatId);
        }
        refreshChatComposer();
        refreshChatTabs();
        if (!streamingChatIds.isEmpty()) {
            act.saveHandler.removeCallbacks(chatBusyWatchdog);
            act.saveHandler.postDelayed(chatBusyWatchdog, 15_000L);
        } else {
            act.saveHandler.removeCallbacks(chatBusyWatchdog);
        }
    }

    boolean isActiveChatStreaming() {
        return isChatStreaming(act.activeChatId);
    }

    private String lastAgentMessageText() {
        return lastAgentMessageTextOf(act.chatPlain.toString());
    }

    String lastAgentMessageTextOf(String plain) {
        if (plain == null) return null;
        int idx = plain.lastIndexOf("\nagent:\n");
        if (idx >= 0) {
            return plain.substring(idx + "\nagent:\n".length());
        }
        if (plain.startsWith("agent:\n")) {
            return plain.substring("agent:\n".length());
        }
        return null;
    }

    /** Agent bubble still showing a live status placeholder (… running, etc.). */
    private boolean isPendingAgentReply() {
        ChatSession cur = activeChat();
        String text = cur != null
                ? lastAgentMessageTextOf(cur.log.toString())
                : lastAgentMessageText();
        if (text == null) return false;
        text = text.trim();
        if (text.isEmpty()) return false;
        if ("…".equals(text)) return true;
        if (text.startsWith("…")) return true;
        if ("running…".equalsIgnoreCase(text) || "running.".equalsIgnoreCase(text)) return true;
        return "running".equalsIgnoreCase(text);
    }

    /** Reconcile UI after restart / lost stream: show stop when agent still running. */
    /**
     * Ask the bridge what it is still running and reattach to all of it.
     *
     * <p>A run belongs to the bridge, not to the request that started it, so the app
     * going away — backgrounded, killed, tailnet dropped — only detaches a listener.
     * On the way back in we replay whatever was produced while nobody was watching
     * and then follow each session to its end. Sessions that finished while we were
     * away are written into their chat so nothing is silently lost.
     */
    void resumeRunningSessions() {
        if (!act.computers.hasHost() || !act.aiEnabled) return; // needs a computer and AI on
        if (act.bridge == null || act.restoring) return;
        act.bridge.listSessions(new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (act.isDead()) return;
                org.json.JSONArray arr = value.optJSONArray("sessions");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("chatId", "");
                    if (id.isEmpty()) continue;
                    if (findChat(id) == null) continue;  // chat this app does not know
                    String status = o.optString("status", "");
                    String text = o.optString("text", "");
                    if ("running".equals(status)) {
                        if (isChatStreaming(id)) continue;  // already following
                        String msg = o.optString("statusMessage", "");
                        if (!text.isEmpty()) setLastAgentMessageForChat(id, text, false);
                        else if (!msg.isEmpty()) setLastAgentMessageForChat(id, "… " + msg, false);
                        followSession(id);
                    } else if (!text.isEmpty() && chatLooksPending(id)) {
                        // Finished, cancelled or interrupted while we were away.
                        finishChatResponse(id, new BridgeClient.ChatResult(
                                status.isEmpty() ? "finished" : status,
                                text,
                                o.isNull("error") ? null : o.optString("error", null)));
                    }
                }
            }

            @Override
            public void onError(String message) {
                // Bridge unreachable: leave placeholders alone, we try again on reconnect.
            }
        });
    }

    /** Last status line per chat, so switching tabs restores the right one. */
    private final java.util.HashMap<String, String> chatActivity = new java.util.HashMap<>();

    /**
     * Show what the agent is doing right now. Kept separate from the message bubble:
     * the bubble is the reply, and once the reply starts streaming it must not be
     * overwritten by "using grep" — which is why status used to vanish the moment any
     * text arrived, leaving long tool stretches looking idle.
     */
    void showAgentActivity(String chatId, String message) {
        if (chatId == null || message == null || message.isEmpty()) return;
        chatActivity.put(chatId, message);
        if (chatId.equals(act.activeChatId)) {
            evalChatJs("setAgentActivity(" + JSONObject.quote(message) + ")");
        }
    }

    private void hideAgentActivity(String chatId) {
        if (chatId != null) chatActivity.remove(chatId);
        if (chatId == null || chatId.equals(act.activeChatId)) {
            evalChatJs("clearAgentActivity()");
        }
    }

    /** Re-assert the bar for whichever chat just came on screen. */
    private void syncAgentActivity() {
        String msg = act.activeChatId != null ? chatActivity.get(act.activeChatId) : null;
        if (msg != null && isChatStreaming(act.activeChatId)) {
            evalChatJs("setAgentActivity(" + JSONObject.quote(msg) + ")");
        } else {
            evalChatJs("clearAgentActivity()");
        }
    }

    /** True when this chat's last agent bubble is still a placeholder. */
    private boolean chatLooksPending(String chatId) {
        if (chatId == null) return false;
        if (chatId.equals(act.activeChatId)) return isPendingAgentReply();
        ChatSession c = findChat(chatId);
        if (c == null || c.log == null) return false;
        String log = c.log.toString();
        int idx = log.lastIndexOf("\nagent:\n");
        String tail = idx >= 0 ? log.substring(idx + "\nagent:\n".length()) : log;
        tail = tail.trim();
        return tail.isEmpty() || tail.startsWith("…");
    }

    /** How many times a lost connection is re-probed before the chat is written off. */
    private static final int SESSION_RECHECK_LIMIT = 12;
    private final java.util.HashMap<String, Integer> sessionRecheckCount = new java.util.HashMap<>();

    /**
     * The stream died but the run may not have. Ask the bridge: still running means
     * reattach, anything else means finish the chat with whatever it produced.
     */
    private void recheckSession(String chatId) {
        if (chatId == null || act.bridge == null) return;
        final int tries = sessionRecheckCount.containsKey(chatId)
                ? sessionRecheckCount.get(chatId) : 0;
        act.bridge.listSessions(new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (act.isDead()) return;
                sessionRecheckCount.remove(chatId);
                org.json.JSONArray arr = value.optJSONArray("sessions");
                JSONObject found = null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && chatId.equals(o.optString("chatId", ""))) {
                            found = o;
                            break;
                        }
                    }
                }
                if (found != null && "running".equals(found.optString("status", ""))) {
                    followSession(chatId);
                    return;
                }
                bumpStreamGen(chatId);
                if (found != null && !found.optString("text", "").isEmpty()) {
                    finishChatResponse(chatId, new BridgeClient.ChatResult(
                            found.optString("status", "finished"),
                            found.optString("text", ""),
                            found.isNull("error") ? null : found.optString("error", null)));
                } else {
                    clearStreaming(chatId);
                    appendToChat(chatId, "warn", "chat timed out — try send again");
                }
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                // Bridge unreachable — tailnet down, most likely. Keep the session
                // marked live and keep probing; the agent is still working.
                if (tries + 1 >= SESSION_RECHECK_LIMIT) {
                    sessionRecheckCount.remove(chatId);
                    bumpStreamGen(chatId);
                    clearStreaming(chatId);
                    appendToChat(chatId, "warn", "lost the connection to " + act.computers.workspaceName() + " — reopen to reattach");
                    return;
                }
                sessionRecheckCount.put(chatId, tries + 1);
                setLastAgentMessageForChat(chatId, "… reconnecting", false);
                markChatStreaming(chatId);
                act.saveHandler.postDelayed(() -> recheckSession(chatId), 5_000L);
            }
        });
    }

    /** Attach to a session the bridge is already running and drive it to completion. */
    private void followSession(String chatId) {
        if (chatId == null || act.bridge == null) return;
        final int sid = bumpStreamGen(chatId);
        markChatStreaming(chatId);
        act.bridge.chatFollow(chatId, new BridgeClient.ChatStreamListener() {
            private boolean gotDelta = false;

            @Override
            public void onStatus(String message) {
                if (!isStreamCurrent(chatId, sid)) return;
                if (message == null || message.isEmpty()) return;
                showAgentActivity(chatId, message);
                if (!gotDelta) {
                    setLastAgentMessageForChat(chatId, "… " + message, false);
                }
                bumpChatBusyWatchdog(chatId);
            }

            @Override
            public void onTool(String name, String detail, String status, String message) {
                if (!isStreamCurrent(chatId, sid)) return;
                String line = message != null && !message.isEmpty()
                        ? message : (name != null ? name : "tool");
                showAgentActivity(chatId,
                        "running".equals(status) ? "using " + line + "…" : line);
                appendToolStepForChat(chatId, line, status);
                gotDelta = true;
                bumpChatBusyWatchdog(chatId);
            }

            @Override
            public void onDelta(String text) {
                if (!isStreamCurrent(chatId, sid)) return;
                if (text == null) return;
                if (turnHasAgent(chatId)) {
                    setLastAgentMessageForChat(chatId, text, false);
                } else {
                    appendToChat(chatId, "agent", text);
                }
                gotDelta = true;
                bumpChatBusyWatchdog(chatId);
            }

            @Override
            public void onDone(BridgeClient.ChatResult value) {
                if (!isStreamCurrent(chatId, sid)) return;
                finishChatResponse(chatId, value);
            }

            @Override
            public void onError(String message) {
                if (!isStreamCurrent(chatId, sid)) return;
                // The run keeps going on the bridge; we only lost the pipe.
                setLastAgentMessageForChat(chatId, "… reconnecting", false);
                act.saveHandler.postDelayed(() -> recheckSession(chatId), 3_000L);
            }
        });
    }

    void syncChatRunState() {
        if (!act.computers.hasHost() || !act.aiEnabled) return; // needs a computer and AI on
        if (act.bridge == null || act.restoring) return;
        if (!isPendingAgentReply()) {
            if (!isActiveChatStreaming()) {
                refreshChatComposer();
            }
            return;
        }
        if (act.activeChatId != null) markChatStreaming(act.activeChatId);
        act.bridge.health(new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject health) {
                if (act.isDead()) return;
                org.json.JSONArray active = health.optJSONArray("activeChats");
                // Mark every bridge-active chat as streaming; clear stale placeholders.
                java.util.HashSet<String> live = new java.util.HashSet<>();
                if (active != null) {
                    for (int i = 0; i < active.length(); i++) {
                        String id = active.optString(i, "");
                        if (!id.isEmpty()) live.add(id);
                    }
                }
                if (act.activeChatId != null && live.contains(act.activeChatId)) {
                    markChatStreaming(act.activeChatId);
                    return;
                }
                // Also adopt any other live runs so tabs show spinners.
                for (String id : live) {
                    markChatStreaming(id);
                }
                if (act.activeChatId != null && !live.contains(act.activeChatId) && isPendingAgentReply()) {
                    setLastAgentMessage("(interrupted — send again)", true);
                    clearStreaming(act.activeChatId);
                }
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                if (act.activeChatId != null) markChatStreaming(act.activeChatId);
            }
        });
    }

    void bumpChatBusyWatchdog(String chatId) {
        if (chatId == null || !streamingChatIds.contains(chatId)) return;
        chatStreamLastBumpMs.put(chatId, System.currentTimeMillis());
        act.saveHandler.removeCallbacks(chatBusyWatchdog);
        act.saveHandler.postDelayed(chatBusyWatchdog, 15_000L);
    }

    void refreshChatComposer() {
        boolean thisStreaming = isActiveChatStreaming();
        if (act.chatAttachButton != null) act.chatAttachButton.setVisibility(View.VISIBLE);
        if (act.chatInput != null) act.chatInput.setVisibility(View.VISIBLE);
        if (act.chatComposerShell != null) act.chatComposerShell.setVisibility(View.VISIBLE);
        refreshAttachRow();
        if (act.sendButton != null) {
            act.sendButton.setEnabled(!thisStreaming);
            act.sendButton.setAlpha(thisStreaming ? 0.45f : 1f);
            act.sendButton.setVisibility(View.VISIBLE);
            act.sendButton.setClickable(!thisStreaming);
        }
        if (act.stopButton != null) {
            act.stopButton.setEnabled(true);
            act.stopButton.setAlpha(1f);
            // Strictly about the chat you are looking at. This used to fall back to
            // "exactly one chat is streaming", which put a stop button in front of an
            // idle session and let it kill a run belonging to a different one.
            boolean showStop = thisStreaming || isPendingAgentReply();
            act.stopButton.setVisibility(showStop ? View.VISIBLE : View.GONE);
        }
        act.chatView.refreshChatRunBanner();
    }

    /** Update the trailing agent bubble; finalRender runs KaTeX. */
    private void setLastAgentMessage(String text, boolean finalRender) {
        setLastAgentMessageForChat(act.activeChatId, text, finalRender);
    }

    void setLastAgentMessageForChat(String chatId, String text, boolean finalRender) {
        if (text == null) text = "";
        // adb install kills the app without a clean onPause — flush disk ASAP.
        if (text.contains("deploying") || text.contains("deploy done")
                || text.contains("adb install") || text.contains("flush session")) {
            act.saveHandler.post(() -> act.persistence.saveSessionNow(true));
        }
        if (!turnHasAgent(chatId)) {
            appendToChat(chatId, "agent", text);
            return;
        }
        boolean visible = chatId == null || chatId.equals(act.activeChatId);
        if (visible) {
            String plain = act.chatPlain.toString();
            int[] span = agentBodySpan(plain);
            if (span == null) {
                appendChat("agent", text);
                return;
            }
            act.chatPlain.setLength(0);
            act.chatPlain.append(plain, 0, span[0]).append(text).append(plain, span[1], plain.length());
            if (finalRender) {
                evalChatJs("replaceLastAgent(" + JSONObject.quote(text) + ")");
                syncActiveFromUi();
                act.persistence.scheduleSave();
            } else {
                // Live path only — never scheduleSave here (main-thread save jank
                // made streaming choppy and dropped the tail of replies).
                evalChatJs("updateLastAgentLive(" + JSONObject.quote(text) + ")");
            }
            refreshChatComposer();
            return;
        }
        ChatSession c = findChat(chatId);
        if (c == null) return;
        String plain = c.log.toString();
        int[] span = agentBodySpan(plain);
        if (span == null) {
            appendToChat(chatId, "agent", text);
            return;
        }
        c.log.setLength(0);
        c.log.append(plain, 0, span[0]).append(text).append(plain, span[1], plain.length());
        c.updatedAt = System.currentTimeMillis();
        if (finalRender) act.persistence.scheduleSave();
    }

    /**
     * Start/end of the latest agent body in {@code plain}, excluding any tool/you/…
     * blocks that follow it. Null if no agent entry exists.
     */
    private static int[] agentBodySpan(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        int idx = plain.lastIndexOf("\nagent:\n");
        int start;
        if (idx >= 0) start = idx + "\nagent:\n".length();
        else if (plain.startsWith("agent:\n")) start = "agent:\n".length();
        else return null;
        String rest = plain.substring(start);
        Matcher m = CHAT_MESSAGE_START.matcher("\n" + rest);
        int end = plain.length();
        if (m.find()) {
            // m.start() indexes into ("\n" + rest); body length inside rest is m.start().
            end = start + Math.max(0, m.start());
        }
        return new int[]{start, end};
    }

    /** True when the current user turn already has an agent block (tools may follow). */
    boolean turnHasAgent(String chatId) {
        String plain = chatLogPlain(chatId);
        if (plain == null || plain.isEmpty()) return false;
        int you = plain.lastIndexOf("\nyou:\n");
        if (plain.startsWith("you:\n") && you < 0) you = 0;
        int agent = plain.lastIndexOf("\nagent:\n");
        if (plain.startsWith("agent:\n") && agent < 0) agent = 0;
        return agent >= 0 && agent > you;
    }

    private void replaceLastAgentPlaceholder(String text) {
        setLastAgentMessage(text, true);
    }

    void appendChat(String who, String text) {
        // With no computer the chat is closed: say it where it can be seen.
        if (!act.computers.hasHost() && ("warn".equals(who) || "error".equals(who))) {
            act.snackbar(text, true);
            return;
        }
        appendToChat(act.activeChatId, who, text);
    }

    void appendToChat(String chatId, String who, String text) {
        if (text == null) text = "";
        boolean visible = chatId == null || chatId.equals(act.activeChatId);
        if (visible) {
            if (act.chatPlain.length() > 0) act.chatPlain.append('\n');
            act.chatPlain.append(who).append(":\n").append(text);
            trimChatLog(act.chatPlain);
            evalChatJs("appendMessage(" + JSONObject.quote(who) + "," + JSONObject.quote(text) + ")");
            syncActiveFromUi();
            act.persistence.scheduleSave();
            return;
        }
        ChatSession c = findChat(chatId);
        if (c == null) return;
        if (c.log.length() > 0) c.log.append('\n');
        c.log.append(who).append(":\n").append(text);
        trimChatLog(c.log);
        c.updatedAt = System.currentTimeMillis();
        act.persistence.scheduleSave();
    }

    /** True when the transcript's last labelled block is an agent prose bubble. */
    private boolean lastChatEntryIsAgent(String chatId) {
        String plain = chatLogPlain(chatId);
        if (plain == null || plain.isEmpty()) return false;
        int you = plain.lastIndexOf("\nyou:\n");
        int agent = plain.lastIndexOf("\nagent:\n");
        int tool = plain.lastIndexOf("\ntool:\n");
        int warn = plain.lastIndexOf("\nwarn:\n");
        int err = plain.lastIndexOf("\nerror:\n");
        if (plain.startsWith("agent:\n") && agent < 0) agent = 0;
        if (plain.startsWith("tool:\n") && tool < 0) tool = 0;
        int last = Math.max(Math.max(you, agent), Math.max(tool, Math.max(warn, err)));
        return last >= 0 && last == agent;
    }

    private String chatLogPlain(String chatId) {
        if (chatId == null || chatId.equals(act.activeChatId)) return act.chatPlain.toString();
        ChatSession c = findChat(chatId);
        return c != null ? c.log.toString() : "";
    }

    private String peekLastAgentText(String chatId) {
        String plain = chatLogPlain(chatId);
        if (plain == null) return "";
        int idx = plain.lastIndexOf("\nagent:\n");
        int start;
        if (idx >= 0) start = idx + "\nagent:\n".length();
        else if (plain.startsWith("agent:\n")) start = "agent:\n".length();
        else return "";
        // Stop at the next labelled block if any (should not happen if last is agent).
        String rest = plain.substring(start);
        Matcher m = CHAT_MESSAGE_START.matcher("\n" + rest);
        if (m.find()) {
            return rest.substring(0, Math.max(0, m.start()));
        }
        return rest;
    }

    /** Append a tool step into the agent message (no checkmarks, one bubble). */
    void appendToolStepForChat(String chatId, String line, String status) {
        if (line == null || line.isEmpty()) return;
        final String clean = stripToolMarks(line);
        if (clean.isEmpty()) return;
        final String st = status == null || status.isEmpty() ? "running" : status;
        String plain = chatLogPlain(chatId);
        boolean updateLast = false;
        if ("completed".equals(st) || "error".equals(st)) {
            if (plain != null) {
                int idx = plain.lastIndexOf("\ntool:\n");
                if (idx >= 0 && idx == plain.lastIndexOf("\ntool:\n")) {
                    String after = plain.substring(idx + "\ntool:\n".length());
                    if (!after.contains("\n") && !after.startsWith("✓") && !after.startsWith("✗")) {
                        updateLast = true;
                    }
                }
            }
        }
        if (updateLast) {
            setLastToolMessageForChat(chatId, clean);
        } else {
            persistChatLine(chatId, "tool", clean);
            boolean visible = chatId == null || chatId.equals(act.activeChatId);
            if (visible) {
                evalChatJs("upsertAgentStep(" + JSONObject.quote(clean) + ","
                        + JSONObject.quote(st) + ")");
            }
        }
    }

    /** Write a labelled chat line to the transcript without assuming DOM shape. */
    private void persistChatLine(String chatId, String who, String text) {
        if (text == null) text = "";
        boolean visible = chatId == null || chatId.equals(act.activeChatId);
        if (visible) {
            if (act.chatPlain.length() > 0) act.chatPlain.append('\n');
            act.chatPlain.append(who).append(":\n").append(text);
            trimChatLog(act.chatPlain);
            syncActiveFromUi();
            act.persistence.scheduleSave();
            return;
        }
        ChatSession c = findChat(chatId);
        if (c == null) return;
        if (c.log.length() > 0) c.log.append('\n');
        c.log.append(who).append(":\n").append(text);
        trimChatLog(c.log);
        c.updatedAt = System.currentTimeMillis();
        act.persistence.scheduleSave();
    }

    private static String stripToolMarks(String line) {
        if (line == null) return "";
        String t = line.trim();
        if (t.startsWith("✓ ") || t.startsWith("✗ ")) return t.substring(2).trim();
        if (t.startsWith("✓") || t.startsWith("✗")) return t.substring(1).trim();
        return line;
    }

    private void setLastToolMessageForChat(String chatId, String text) {
        text = stripToolMarks(text);
        boolean visible = chatId == null || chatId.equals(act.activeChatId);
        if (visible) {
            String plain = act.chatPlain.toString();
            int idx = plain.lastIndexOf("\ntool:\n");
            if (idx < 0 && plain.startsWith("tool:\n")) idx = 0;
            if (idx < 0) {
                appendToChat(chatId, "tool", text);
                return;
            }
            int start = idx == 0 && plain.startsWith("tool:\n")
                    ? "tool:\n".length()
                    : idx + "\ntool:\n".length();
            act.chatPlain.setLength(0);
            act.chatPlain.append(plain, 0, start).append(text);
            evalChatJs("replaceLastTool(" + JSONObject.quote(text) + ")");
            syncActiveFromUi();
            return;
        }
        ChatSession c = findChat(chatId);
        if (c == null) return;
        String plain = c.log.toString();
        int idx = plain.lastIndexOf("\ntool:\n");
        if (idx < 0 && plain.startsWith("tool:\n")) idx = 0;
        if (idx < 0) {
            appendToChat(chatId, "tool", text);
            return;
        }
        int start = idx == 0 && plain.startsWith("tool:\n")
                ? "tool:\n".length()
                : idx + "\ntool:\n".length();
        c.log.setLength(0);
        c.log.append(plain, 0, start).append(text);
        c.updatedAt = System.currentTimeMillis();
    }

    /**
     * Caps a chat transcript. The whole log is re-serialized on every autosave and
     * re-injected into the WebView on every switch, so unbounded growth costs on each.
     * Trims at a message boundary so the retained text stays parseable.
     */
    private static final Pattern CHAT_MESSAGE_START =
            Pattern.compile("\\n(?=(?:you|agent|tool|warn|error|system):)");

    private static void trimChatLog(StringBuilder log) {
        if (log.length() <= MAX_CHAT_LOG_CHARS) return;
        int cut = log.length() - MAX_CHAT_LOG_CHARS;
        // Must cut at a message start, not just any newline: message bodies contain
        // newlines, and the chat view re-labels an unlabelled leading fragment as an
        // agent turn — so a mid-body cut would show your own words as the agent's.
        Matcher m = CHAT_MESSAGE_START.matcher(log);
        if (m.find(cut)) {
            log.delete(0, m.start() + 1);
        } else {
            log.delete(0, cut);
        }
    }

    void evalChatJs(String script) {
        // The floating instant chat mirrors the main chat view, update for update.
        if (act.miniChat != null) act.miniChat.evalJs(script);
        final WebView web = act.chatWeb;
        if (web == null || !act.chatWebReady) return;
        // Capture the view: the field is nulled in onDestroy while posts are pending.
        web.post(() -> {
            if (act.chatWeb == web) web.evaluateJavascript(script, null);
        });
    }
}
