package me.hapke.inkside;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;

import java.io.ByteArrayOutputStream;

/** Create / grow blank A4 PDF byte buffers (annotations live in app data, not here). */
final class PdfDocumentIo {
    /** A4 portrait in PDF points. */
    static final int A4_WIDTH_PT = 595;
    static final int A4_HEIGHT_PT = 842;

    private PdfDocumentIo() {}

    /** One blank A4 page. */
    static byte[] createBlankA4(int pageCount) throws Exception {
        int n = Math.max(1, pageCount);
        PdfDocument doc = new PdfDocument();
        for (int i = 0; i < n; i++) {
            PdfDocument.PageInfo info =
                    new PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, i + 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas c = page.getCanvas();
            c.drawColor(Color.WHITE);
            doc.finishPage(page);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.writeTo(bos);
        doc.close();
        return bos.toByteArray();
    }
}
