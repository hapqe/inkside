package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sticker and stamp library: anything lassoed can be kept (handwriting, images,
 * text boxes) and dropped onto any page later, as often as wanted. Kept on the tablet,
 * shared by every document.
 */
final class Stickers {
    private static final String FILE = "stickers.json";
    private static final int THUMB_PX = 192;

    private final MainActivity act;
    private final List<JSONObject> items = new ArrayList<>();
    private boolean loaded;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stickers");
        t.setDaemon(true);
        return t;
    });

    Stickers(MainActivity act) {
        this.act = act;
    }

    private File file() {
        return new File(act.getFilesDir(), FILE);
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (!f.isFile()) return;
            JSONArray arr = new JSONObject(new String(LocalWorkspace.readAll(f), StandardCharsets.UTF_8))
                    .optJSONArray("stickers");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optJSONObject("content") != null) items.add(o);
            }
        } catch (Exception ignored) {
            // An unreadable library starts empty rather than blocking the canvas.
        }
    }

    private void persist() {
        final List<JSONObject> snapshot = new ArrayList<>(items);
        writer.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (JSONObject o : snapshot) arr.put(o);
                JSONObject root = new JSONObject();
                root.put("stickers", arr);
                File tmp = new File(act.getFilesDir(), FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file());
            } catch (Exception ignored) {
            }
        });
    }

    /** The selection becomes a sticker at the front of the library. */
    void saveSelection() {
        if (act.canvas == null || !act.canvas.hasActiveSelection()) return;
        load();
        try {
            JSONObject content = act.canvas.selectionAsSticker();
            if (content == null) return;
            JSONObject o = new JSONObject();
            o.put("id", java.util.UUID.randomUUID().toString());
            o.put("content", content);
            Bitmap thumb = act.canvas.renderSelectionThumbnail(THUMB_PX);
            if (thumb != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.PNG, 100, bos);
                o.put("thumb", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                thumb.recycle();
            }
            items.add(0, o);
            persist();
            refreshStrip();
            act.snackbar("Saved to stickers \u2014 find it under the sticker tool", false);
        } catch (Exception e) {
            act.snackbar("Could not save the sticker: " + e.getMessage(), false);
        }
    }

    /** What a sticker drag carries to the canvas. */
    static final class StickerDrag {
        final JSONObject content;

        StickerDrag(JSONObject content) {
            this.content = content;
        }
    }

    private LinearLayout strip;
    private android.widget.TextView emptyHint;

    /**
     * The row under the toolbar while the sticker tool is open: every sticker as a
     * thumbnail. Drag one onto the page to drop it there; a tap puts it in the middle
     * of the view; a long press deletes it from the library.
     */
    View buildStrip() {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(strip);
        emptyHint = new android.widget.TextView(act);
        emptyHint.setText("No stickers yet");
        emptyHint.setTextColor(act.M3_ON_SURFACE_VARIANT);
        emptyHint.setTextSize(13);
        emptyHint.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10));
        row.addView(emptyHint);
        scroll.addView(row);
        return scroll;
    }

    /** Fills the strip from the library (after a save or delete, and when it opens). */
    void refreshStrip() {
        if (strip == null) return;
        load();
        strip.removeAllViews();
        emptyHint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyHint.setTextColor(act.M3_ON_SURFACE_VARIANT);
        int slop = android.view.ViewConfiguration.get(act).getScaledTouchSlop();
        for (final JSONObject item : items) {
            ImageView cell = new ImageView(act);
            cell.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = act.dp(6);
            cell.setPadding(pad, pad, pad, pad);
            Bitmap thumb = decodeThumb(item.optString("thumb", ""));
            if (thumb != null) cell.setImageBitmap(thumb);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(act.dp(12));
            cell.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                    null, mask));
            cell.setContentDescription("Sticker: drag onto the page, or tap to place");
            cell.setOnClickListener(v -> placeInView(item));
            cell.setOnLongClickListener(v -> {
                confirmDelete(item);
                return true;
            });
            final float[] down = new float[2];
            cell.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getRawX() - down[0], e.getRawY() - down[1]) < slop) return false;
                        v.cancelLongPress();
                        v.setPressed(false);
                        android.content.ClipData data = android.content.ClipData.newPlainText("sticker", "sticker");
                        v.startDragAndDrop(data, new View.DragShadowBuilder(v),
                                new StickerDrag(item.optJSONObject("content")), 0);
                        return true;
                    default:
                        return false;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(act.dp(56), act.dp(56));
            lp.rightMargin = act.dp(6);
            strip.addView(cell, lp);
        }
    }

    /** A drop on the canvas: the sticker centred where it was let go. */
    void placeAt(StickerDrag drag, float worldX, float worldY) {
        if (act.canvas == null || drag == null) return;
        try {
            act.canvas.placeStickerAt(drag.content, worldX, worldY);
            act.persistence.scheduleSave();
        } catch (Exception e) {
            act.snackbar("Could not place the sticker: " + e.getMessage(), false);
        }
    }

    private void placeInView(JSONObject item) {
        if (act.canvas == null || !act.canvas.hasDocument()) return;
        float[] c = act.canvas.getViewCenterWorld();
        placeAt(new StickerDrag(item.optJSONObject("content")), c[0], c[1]);
    }

    private void confirmDelete(JSONObject item) {
        new M3Dialog.Builder(act)
                .setTitle("Delete sticker?")
                .setMessage("It is removed from the library. Copies already on pages stay.")
                .setPositiveButton("Delete", (d, w) -> {
                    items.remove(item);
                    persist();
                    refreshStrip();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static Bitmap decodeThumb(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }
}
