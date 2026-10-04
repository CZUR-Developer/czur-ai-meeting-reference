package com.czur.aimeeting.reference;

import android.app.Activity;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 本场 PDF 打开与定位入口；Android 8/9 由系统文件选择器完成公共目录导出。 */
public final class LocalResultActivity extends Activity {
    static final String EXTRA_URI = "pdfUri";
    static final String EXTRA_CACHE_PATH = "pdfCachePath";
    static final String EXTRA_FILE_NAME = "pdfFileName";
    private static final String EXTRA_AUTOMATIC_DOWNLOAD = "automaticDownload";
    private static final String PREFS = "local_pdf_result";
    private static final int CREATE_PDF = 1;
    private static final String PAGE_INDEX = "pageIndex";
    private Uri pdfUri;
    private String cachePath;
    private String fileName;
    private TextView status;
    private Button open;
    private Button locate;
    private Button save;
    private Button previousPage;
    private Button nextPage;
    private TextView pageLabel;
    private ImageView pageImage;
    private ScrollView pageScroll;
    private ParcelFileDescriptor pdfFile;
    private PdfRenderer pdfRenderer;
    private Bitmap pageBitmap;
    private int pageIndex;
    private boolean automaticDownload;

    static void remember(Context context, LocalPdfWriter.SavedPdf pdf,
            boolean automaticDownload) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(EXTRA_URI, pdf.uri() == null ? null : pdf.uri().toString())
                .putString(EXTRA_CACHE_PATH, pdf.cachePath())
                .putString(EXTRA_FILE_NAME, pdf.displayName())
                .putBoolean(EXTRA_AUTOMATIC_DOWNLOAD, automaticDownload)
                .apply();
    }

    static Intent recentIntent(Context context) {
        var prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = prefs.getString(EXTRA_FILE_NAME, null);
        if (name == null) return null;
        Intent intent = new Intent(context, LocalResultActivity.class);
        intent.putExtra(EXTRA_URI, prefs.getString(EXTRA_URI, null));
        intent.putExtra(EXTRA_CACHE_PATH, prefs.getString(EXTRA_CACHE_PATH, null));
        intent.putExtra(EXTRA_FILE_NAME, name);
        intent.putExtra(EXTRA_AUTOMATIC_DOWNLOAD,
                prefs.getBoolean(EXTRA_AUTOMATIC_DOWNLOAD, false));
        return intent;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String uriValue = getIntent().getStringExtra(EXTRA_URI);
        pdfUri = uriValue == null ? null : Uri.parse(uriValue);
        automaticDownload = getIntent().getBooleanExtra(EXTRA_AUTOMATIC_DOWNLOAD,
                pdfUri != null);
        cachePath = getIntent().getStringExtra(EXTRA_CACHE_PATH);
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (savedInstanceState != null) {
            String savedUri = savedInstanceState.getString(EXTRA_URI);
            if (savedUri != null) pdfUri = Uri.parse(savedUri);
            cachePath = savedInstanceState.getString(EXTRA_CACHE_PATH);
            pageIndex = savedInstanceState.getInt(PAGE_INDEX, 0);
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        status = new TextView(this);
        status.setTextSize(20);
        layout.addView(status);
        open = new Button(this);
        open.setText("在应用内预览 PDF");
        open.setOnClickListener(view -> openPdf());
        layout.addView(open);
        locate = new Button(this);
        locate.setText("在文件中定位");
        locate.setOnClickListener(view -> locatePdf());
        layout.addView(locate);
        save = new Button(this);
        save.setText("保存到公共下载目录");
        save.setOnClickListener(view -> saveLegacy());
        layout.addView(save);
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        previousPage = new Button(this);
        previousPage.setText("上一页");
        previousPage.setOnClickListener(view -> showPage(pageIndex - 1));
        pages.addView(previousPage, new LinearLayout.LayoutParams(0, -2, 1));
        pageLabel = new TextView(this);
        pageLabel.setText("PDF 预览");
        pageLabel.setGravity(android.view.Gravity.CENTER);
        pages.addView(pageLabel, new LinearLayout.LayoutParams(0, -1, 1));
        nextPage = new Button(this);
        nextPage.setText("下一页");
        nextPage.setOnClickListener(view -> showPage(pageIndex + 1));
        pages.addView(nextPage, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(pages);
        pageScroll = new ScrollView(this);
        pageImage = new ImageView(this);
        pageImage.setAdjustViewBounds(true);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageScroll.addView(pageImage);
        layout.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        render();
        if (savedInstanceState == null && pdfUri == null) saveLegacy();
        else if (pdfUri != null) openPdf();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (pdfUri != null) outState.putString(EXTRA_URI, pdfUri.toString());
        outState.putString(EXTRA_CACHE_PATH, cachePath);
        outState.putInt(PAGE_INDEX, pageIndex);
        super.onSaveInstanceState(outState);
    }

    private void render() {
        boolean saved = pdfUri != null;
        status.setText(saved ? automaticDownload
                ? "本场 PDF 已保存到 下载/AI会议纪要/" + fileName
                : "本场 PDF 已保存到您选择的位置：" + fileName
                : "PDF 已生成。Android 8/9 请在系统选择器中选择 下载/AI会议纪要 后保存；取消后可再次点击保存。");
        open.setEnabled(saved);
        locate.setEnabled(saved);
        save.setVisibility(saved ? View.GONE : View.VISIBLE);
        if (!saved) {
            previousPage.setEnabled(false);
            nextPage.setEnabled(false);
        }
    }

    private void saveLegacy() {
        if (cachePath == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildRootUri("com.android.providers.downloads.documents",
                            "downloads"));
        }
        try {
            startActivityForResult(intent, CREATE_PDF);
        } catch (ActivityNotFoundException failure) {
            status.setText("系统没有可用的 PDF 保存选择器");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_PDF || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri destination = data.getData();
        try (FileInputStream input = new FileInputStream(cachePath);
                OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IOException("无法打开目标文件");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            pdfUri = destination;
            new File(cachePath).delete();
            cachePath = null;
            remember(this, new LocalPdfWriter.SavedPdf(pdfUri, null, fileName), false);
            render();
            openPdf();
        } catch (IOException failure) {
            status.setText("PDF 保存失败：" + failure.getMessage() + "；请重试");
        }
    }

    private void openPdf() {
        if (pdfUri == null) return;
        try {
            if (pdfRenderer == null) {
                pdfFile = getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pdfFile == null) throw new IOException("无法读取已保存 PDF");
                pdfRenderer = new PdfRenderer(pdfFile);
            }
            showPage(Math.min(pageIndex, pdfRenderer.getPageCount() - 1));
        } catch (IOException | RuntimeException failure) {
            status.setText("PDF 预览失败：" + failure.getMessage());
        }
    }

    private void showPage(int index) {
        if (pdfRenderer == null || index < 0 || index >= pdfRenderer.getPageCount()) return;
        try (PdfRenderer.Page page = pdfRenderer.openPage(index)) {
            float scale = Math.min(2.0f, 2048f / Math.max(page.getWidth(), page.getHeight()));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)),
                    Math.max(1, (int) (page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(android.graphics.Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            Bitmap old = pageBitmap;
            pageBitmap = bitmap;
            pageImage.setImageBitmap(bitmap);
            if (old != null) old.recycle();
            pageIndex = index;
            pageLabel.setText((index + 1) + " / " + pdfRenderer.getPageCount());
            previousPage.setEnabled(index > 0);
            nextPage.setEnabled(index + 1 < pdfRenderer.getPageCount());
            pageScroll.post(() -> pageScroll.scrollTo(0, 0));
        } catch (RuntimeException failure) {
            status.setText("PDF 第 " + (index + 1) + " 页预览失败：" + failure.getMessage());
        }
    }

    @Override protected void onDestroy() {
        pageImage.setImageDrawable(null);
        if (pageBitmap != null) pageBitmap.recycle();
        if (pdfRenderer != null) pdfRenderer.close();
        if (pdfFile != null) {
            try { pdfFile.close(); } catch (IOException ignored) { }
        }
        super.onDestroy();
    }

    private void locatePdf() {
        if (pdfUri == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pdfUri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException failure) {
            status.setText("请在文件管理器中打开 下载/AI会议纪要 查找 PDF");
        }
    }
}
