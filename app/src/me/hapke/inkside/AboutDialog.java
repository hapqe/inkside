package me.hapke.inkside;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** ⋮ → About: what Inkside is, its version, the licence and the project page on GitHub. */
final class AboutDialog {
    static final String REPO_URL = "https://github.com/hapqe/inkside";

    private AboutDialog() {}

    static void show(MainActivity act) {
        String version = "";
        try {
            PackageInfo info = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            version = info.versionName != null ? info.versionName : "";
        } catch (Exception ignored) {
            // Version is cosmetic.
        }

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // The app icon's handwritten i on its paper tile, zoomed in so the mark fills the tile.
        ImageView logo = new ImageView(act);
        logo.setImageResource(R.drawable.ic_launcher_fg);
        android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
        tile.setColor(0xFFF5F1E8);
        tile.setCornerRadius(act.dp(16));
        logo.setBackground(tile);
        logo.setClipToOutline(true);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        final int box = act.dp(64);
        logo.post(() -> {
            android.graphics.drawable.Drawable d = logo.getDrawable();
            if (d == null || d.getIntrinsicWidth() <= 0) return;
            float iw = d.getIntrinsicWidth();
            float s = box / iw * 1.9f;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(s, s);
            m.postTranslate(box / 2f - 55f / 108f * iw * s, box / 2f - 52f / 108f * iw * s);
            logo.setImageMatrix(m);
        });
        head.addView(logo, new LinearLayout.LayoutParams(box, box));
        LinearLayout names = new LinearLayout(act);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(line(act, "Inkside", M3Dialog.onSurface, 20));
        names.addView(line(act, version.isEmpty() ? "" : "Version " + version, M3Dialog.onSurfaceVariant, 13));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = act.dp(MainActivity.SPACE_MD);
        head.addView(names, nlp);
        body.addView(head);

        TextView about = line(act, "A tablet notebook for learning and coding: PDF documents, stylus ink and an AI "
                + "agent that can see what you circled. Open source, and it works on its own — a computer "
                + "running the Inkside host adds the agent, scripts and voice.", M3Dialog.onSurfaceVariant, 14);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = act.dp(MainActivity.SPACE_LG);
        body.addView(about, alp);

        TextView licence = line(act, "Released under the Apache License 2.0. Uses ML Kit Digital Ink, "
                + "PdfBox-Android, KaTeX and pdf.js — see NOTICE in the repository.",
                M3Dialog.onSurfaceVariant, 12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = act.dp(MainActivity.SPACE_MD);
        body.addView(licence, llp);

        TextView github = act.panelAction("View on GitHub", true, () -> {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
            } catch (Exception e) {
                act.statusToast("No browser available");
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = act.dp(MainActivity.SPACE_LG);
        body.addView(github, glp);

        new M3Dialog.Builder(act)
                .setTitle("About")
                .setView(body)
                .setPositiveButton("Close", null)
                .show();
    }

    private static TextView line(MainActivity act, String text, int color, float sp) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }
}
