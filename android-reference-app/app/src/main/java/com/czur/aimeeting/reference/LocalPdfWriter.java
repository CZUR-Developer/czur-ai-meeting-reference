package com.czur.aimeeting.reference;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import com.czur.aimeeting.reference.meeting.LocalMeetingResult;
import com.czur.aimeeting.reference.meeting.LocalPdfContent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** A4 本地结果 PDF。Android 10+ 自动保存到公共下载目录，旧版交给系统文档选择器导出。 */
final class LocalPdfWriter {
    record SavedPdf(Uri uri, String cachePath, String displayName) { }
    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 42;
    private static final int BOTTOM = HEIGHT - MARGIN;
    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private PdfDocument document;
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber;
    private float y;

    LocalPdfWriter(Context context) { this.context = context; }

    SavedPdf write(String meetingId, LocalMeetingResult result) throws IOException {
        String fileName = "会议纪要-" + meetingId.replaceAll("[^A-Za-z0-9_-]", "_")
                + "-" + System.currentTimeMillis() + ".pdf";
        document = new PdfDocument();
        try {
            newPage();
            paragraph(result.title() == null || result.title().isBlank()
                    ? "参考会议" : result.title(), 22, true);
            paragraph("会议编号：" + meetingId, 11, false);
            List<LocalPdfContent.Entry> entries = LocalPdfContent.from(result);
            for (LocalPdfContent.Entry entry : entries) {
                switch (entry.kind()) {
                    case HEADING -> paragraph(entry.text(), 17, true);
                    case TEXT -> paragraph(entry.text(), 12, false);
                    case IMAGE -> image(entry.text(), entry.imageBase64());
                }
            }
            finishPage();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return writeDownloads(fileName);
            }
            // API 26–28 没有免权限的 MediaStore.Downloads 写入；先放 App 缓存，再由用户选下载位置。
            File file = new File(context.getCacheDir(), fileName);
            try (OutputStream output = new FileOutputStream(file)) {
                document.writeTo(output);
            }
            return new SavedPdf(null, file.getAbsolutePath(), fileName);
        } finally {
            document.close();
        }
    }

    private SavedPdf writeDownloads(String fileName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AI会议纪要");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法在公共下载目录创建 PDF");
        boolean complete = false;
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("无法写入下载目录中的 PDF");
                document.writeTo(output);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, ready, null, null) != 1) {
                throw new IOException("PDF 写入后无法发布到下载目录");
            }
            complete = true;
            return new SavedPdf(uri, null, fileName);
        } finally {
            if (!complete) context.getContentResolver().delete(uri, null, null);
        }
    }

    private void newPage() {
        page = document.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT,
                ++pageNumber).create());
        canvas = page.getCanvas();
        y = MARGIN;
    }

    private void finishPage() {
        if (page != null) {
            document.finishPage(page);
            page = null;
        }
    }

    private void ensure(float height) {
        if (y + height <= BOTTOM) return;
        finishPage();
        newPage();
    }

    private void paragraph(String value, int size, boolean bold) {
        if (value == null || value.isEmpty()) return;
        paint.setColor(0xff202124);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        float lineHeight = size * 1.5f;
        for (String sourceLine : value.split("\\n", -1)) {
            if (sourceLine.isEmpty()) {
                ensure(lineHeight);
                y += lineHeight;
                continue;
            }
            int offset = 0;
            while (offset < sourceLine.length()) {
                int count = paint.breakText(sourceLine, offset, sourceLine.length(), true,
                        WIDTH - 2f * MARGIN, null);
                if (count <= 0) count = 1;
                // 不在 Unicode 代理项之间换行。
                if (offset + count < sourceLine.length()
                        && Character.isHighSurrogate(sourceLine.charAt(offset + count - 1))) {
                    count--;
                }
                if (count <= 0) count = 2;
                ensure(lineHeight);
                canvas.drawText(sourceLine, offset, offset + count, MARGIN, y + size, paint);
                y += lineHeight;
                offset += count;
            }
        }
        y += bold ? 9 : 5;
    }

    private void image(String caption, String jpegBase64) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.decode(jpegBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("演示画面 JPEG 编码错误", invalid);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 1600);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("演示画面不是有效 JPEG");
        try {
            paragraph(caption, 12, false);
            float maxHeight = HEIGHT - 2f * MARGIN - 35;
            float scale = Math.min((WIDTH - 2f * MARGIN) / bitmap.getWidth(),
                    maxHeight / bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            ensure(height + 14);
            canvas.drawBitmap(bitmap, null,
                    new android.graphics.RectF(MARGIN, y, MARGIN + width, y + height), paint);
            y += height + 14;
        } finally {
            bitmap.recycle();
        }
    }
}
