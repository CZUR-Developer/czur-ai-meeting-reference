package com.czur.aimeeting.reference

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** 本场 PDF 打开与定位入口；Android 8/9 由系统文件选择器完成公共目录导出。 */
class LocalResultActivity : Activity() {
    private var pdfUri: Uri? = null
    private var cachePath: String? = null
    private var fileName: String? = null
    private lateinit var status: TextView
    private lateinit var open: Button
    private lateinit var locate: Button
    private lateinit var save: Button
    private lateinit var previousPage: Button
    private lateinit var nextPage: Button
    private lateinit var pageLabel: TextView
    private lateinit var pageImage: ImageView
    private lateinit var pageScroll: ScrollView
    private var pdfFile: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pageBitmap: Bitmap? = null
    private var pageIndex = 0
    private var automaticDownload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        automaticDownload = intent.getBooleanExtra(EXTRA_AUTOMATIC_DOWNLOAD, pdfUri != null)
        cachePath = intent.getStringExtra(EXTRA_CACHE_PATH)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME)
        if (savedInstanceState != null) {
            savedInstanceState.getString(EXTRA_URI)?.let { pdfUri = Uri.parse(it) }
            cachePath = savedInstanceState.getString(EXTRA_CACHE_PATH)
            pageIndex = savedInstanceState.getInt(PAGE_INDEX, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        status = TextView(this).apply { textSize = 20f }
        layout.addView(status)
        open = Button(this).apply {
            text = "在应用内预览 PDF"
            setOnClickListener { openPdf() }
        }
        layout.addView(open)
        locate = Button(this).apply {
            text = "在文件中定位"
            setOnClickListener { locatePdf() }
        }
        layout.addView(locate)
        save = Button(this).apply {
            text = "保存到公共下载目录"
            setOnClickListener { saveLegacy() }
        }
        layout.addView(save)
        val pages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previousPage = Button(this).apply {
            text = "上一页"
            setOnClickListener { showPage(pageIndex - 1) }
        }
        pages.addView(previousPage, LinearLayout.LayoutParams(0, -2, 1f))
        pageLabel = TextView(this).apply {
            text = "PDF 预览"
            gravity = Gravity.CENTER
        }
        pages.addView(pageLabel, LinearLayout.LayoutParams(0, -1, 1f))
        nextPage = Button(this).apply {
            text = "下一页"
            setOnClickListener { showPage(pageIndex + 1) }
        }
        pages.addView(nextPage, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(pages)
        pageScroll = ScrollView(this)
        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        pageScroll.addView(pageImage)
        layout.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        render()
        if (savedInstanceState == null && pdfUri == null) saveLegacy()
        else if (pdfUri != null) openPdf()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pdfUri?.let { outState.putString(EXTRA_URI, it.toString()) }
        outState.putString(EXTRA_CACHE_PATH, cachePath)
        outState.putInt(PAGE_INDEX, pageIndex)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val saved = pdfUri != null
        status.text = if (saved) {
            if (automaticDownload) "本场 PDF 已保存到 下载/AI会议纪要/$fileName"
            else "本场 PDF 已保存到您选择的位置：$fileName"
        } else {
            "PDF 已生成。Android 8/9 请在系统选择器中选择 下载/AI会议纪要 后保存；取消后可再次点击保存。"
        }
        open.isEnabled = saved
        locate.isEnabled = saved
        save.visibility = if (saved) View.GONE else View.VISIBLE
        if (!saved) {
            previousPage.isEnabled = false
            nextPage.isEnabled = false
        }
    }

    private fun saveLegacy() {
        if (cachePath == null) return
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads")
                )
            }
        }
        try {
            startActivityForResult(create, CREATE_PDF)
        } catch (failure: ActivityNotFoundException) {
            status.text = "系统没有可用的 PDF 保存选择器"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CREATE_PDF || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        try {
            FileInputStream(cachePath!!).use { input ->
                contentResolver.openOutputStream(destination).use { output ->
                    if (output == null) throw IOException("无法打开目标文件")
                    input.copyTo(output, 8192)
                    pdfUri = destination
                    File(cachePath!!).delete()
                    cachePath = null
                    remember(this, LocalPdfWriter.SavedPdf(pdfUri, null, fileName!!), false)
                    render()
                    openPdf()
                }
            }
        } catch (failure: IOException) {
            status.text = "PDF 保存失败：${failure.message}；请重试"
        }
    }

    private fun openPdf() {
        val uri = pdfUri ?: return
        try {
            if (pdfRenderer == null) {
                pdfFile = contentResolver.openFileDescriptor(uri, "r")
                val descriptor = pdfFile ?: throw IOException("无法读取已保存 PDF")
                pdfRenderer = PdfRenderer(descriptor)
            }
            showPage(minOf(pageIndex, pdfRenderer!!.pageCount - 1))
        } catch (failure: IOException) {
            status.text = "PDF 预览失败：${failure.message}"
        } catch (failure: RuntimeException) {
            status.text = "PDF 预览失败：${failure.message}"
        }
    }

    /** 一次只持有当前预览位图；切页和退出时释放旧页，PDF 页句柄在本次渲染后关闭。 */
    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= renderer.pageCount) return
        try {
            renderer.openPage(index).use { page ->
                val scale = minOf(2f, 2048f / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(
                    maxOf(1, (page.width * scale).toInt()),
                    maxOf(1, (page.height * scale).toInt()),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val old = pageBitmap
                pageBitmap = bitmap
                pageImage.setImageBitmap(bitmap)
                old?.recycle()
                pageIndex = index
                pageLabel.text = "${index + 1} / ${renderer.pageCount}"
                previousPage.isEnabled = index > 0
                nextPage.isEnabled = index + 1 < renderer.pageCount
                pageScroll.post { pageScroll.scrollTo(0, 0) }
            }
        } catch (failure: RuntimeException) {
            status.text = "PDF 第 ${index + 1} 页预览失败：${failure.message}"
        }
    }

    override fun onDestroy() {
        pageImage.setImageDrawable(null)
        pageBitmap?.recycle()
        pdfRenderer?.close()
        try {
            pdfFile?.close()
        } catch (_: IOException) {
            // 保持原页面释放策略：描述符关闭失败不影响 Activity 完成销毁。
        }
        super.onDestroy()
    }

    private fun locatePdf() {
        val uri = pdfUri ?: return
        val locate = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        try {
            startActivity(locate)
        } catch (failure: ActivityNotFoundException) {
            status.text = "请在文件管理器中打开 下载/AI会议纪要 查找 PDF"
        }
    }

    companion object {
        const val EXTRA_URI = "pdfUri"
        const val EXTRA_CACHE_PATH = "pdfCachePath"
        const val EXTRA_FILE_NAME = "pdfFileName"
        private const val EXTRA_AUTOMATIC_DOWNLOAD = "automaticDownload"
        private const val PREFS = "local_pdf_result"
        private const val CREATE_PDF = 1
        private const val PAGE_INDEX = "pageIndex"

        /** 保存最近一次可回看的结果位置；旧系统未完成导出时保留缓存路径，允许再次保存。 */
        @JvmStatic
        fun remember(context: Context, pdf: LocalPdfWriter.SavedPdf, automaticDownload: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(EXTRA_URI, pdf.uri()?.toString())
                .putString(EXTRA_CACHE_PATH, pdf.cachePath())
                .putString(EXTRA_FILE_NAME, pdf.displayName())
                .putBoolean(EXTRA_AUTOMATIC_DOWNLOAD, automaticDownload)
                .apply()
        }

        /** 无历史结果时返回 null；页面重开仍走同一个 PDF 预览与系统导出流程。 */
        @JvmStatic
        fun recentIntent(context: Context): Intent? {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val name = prefs.getString(EXTRA_FILE_NAME, null) ?: return null
            return Intent(context, LocalResultActivity::class.java).apply {
                putExtra(EXTRA_URI, prefs.getString(EXTRA_URI, null))
                putExtra(EXTRA_CACHE_PATH, prefs.getString(EXTRA_CACHE_PATH, null))
                putExtra(EXTRA_FILE_NAME, name)
                putExtra(EXTRA_AUTOMATIC_DOWNLOAD, prefs.getBoolean(EXTRA_AUTOMATIC_DOWNLOAD, false))
            }
        }
    }
}
