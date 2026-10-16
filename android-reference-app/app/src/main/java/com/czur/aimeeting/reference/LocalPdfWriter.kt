package com.czur.aimeeting.reference

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.reference.meeting.LocalPdfContent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A4 PDF；Android 10+ 写公共下载，旧版本缓存后交系统文档选择器。工作线程调用。 */
class LocalPdfWriter(private val context: Context) {
    data class SavedPdf(private val uri: Uri?, private val cachePath: String?, private val displayName: String) {
        fun uri() = uri
        fun cachePath() = cachePath
        fun displayName() = displayName
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private lateinit var document: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var pageNumber = 0
    private var y = 0f

    @Throws(IOException::class)
    fun write(meetingId: String, result: MeetingResult): SavedPdf {
        val fileName = "会议纪要-${meetingId.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${System.currentTimeMillis()}.pdf"
        document = PdfDocument()
        try {
            newPage()
            paragraph(result.title()?.takeIf { it.isNotBlank() } ?: "参考会议", 22, true)
            paragraph("会议编号：$meetingId", 11, false)
            for (entry in LocalPdfContent.from(result)) when (entry.kind()) {
                LocalPdfContent.Kind.HEADING -> paragraph(entry.text(), 17, true)
                LocalPdfContent.Kind.TEXT -> paragraph(entry.text(), 12, false)
                LocalPdfContent.Kind.IMAGE -> image(entry.text(), entry.jpeg()!!)
            }
            finishPage()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return writeDownloads(fileName)
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { document.writeTo(it) }
            return SavedPdf(null, file.absolutePath, fileName)
        } finally { document.close() }
    }

    private fun writeDownloads(fileName: String): SavedPdf {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI会议纪要")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在公共下载目录创建 PDF")
        var complete = false
        try {
            val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入下载目录中的 PDF")
            output.use { document.writeTo(it) }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (context.contentResolver.update(uri, ready, null, null) != 1) throw IOException("PDF 写入后无法发布到下载目录")
            complete = true
            return SavedPdf(uri, null, fileName)
        } finally { if (!complete) context.contentResolver.delete(uri, null, null) }
    }
    private fun newPage() {
        val created = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pageNumber).create())
        page = created
        canvas = created.canvas
        y = MARGIN.toFloat()
    }
    private fun finishPage() { page?.let { document.finishPage(it) }; page = null }
    private fun ensure(height: Float) { if (y + height > BOTTOM) { finishPage(); newPage() } }
    private fun paragraph(value: String?, size: Int, bold: Boolean) {
        if (value.isNullOrEmpty()) return
        paint.color = 0xff202124.toInt()
        paint.textSize = size.toFloat()
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        val lineHeight = size * 1.5f
        for (sourceLine in value.split("\n")) {
            if (sourceLine.isEmpty()) { ensure(lineHeight); y += lineHeight; continue }
            var offset = 0
            while (offset < sourceLine.length) {
                var count = paint.breakText(sourceLine, offset, sourceLine.length, true, WIDTH - 2f * MARGIN, null)
                if (count <= 0) count = 1
                // 不在 Unicode 代理项之间换行，维持原始 PDF 行切分规则。
                if (offset + count < sourceLine.length && Character.isHighSurrogate(sourceLine[offset + count - 1])) count--
                if (count <= 0) count = 2
                ensure(lineHeight)
                canvas.drawText(sourceLine, offset, offset + count, MARGIN.toFloat(), y + size, paint)
                y += lineHeight
                offset += count
            }
        }
        y += if (bold) 9 else 5
    }
    private fun image(caption: String, bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 1600)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("演示画面不是有效 JPEG")
        try {
            paragraph(caption, 12, false)
            val scale = minOf((WIDTH - 2f * MARGIN) / bitmap.width, (HEIGHT - 2f * MARGIN - 35) / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            ensure(height + 14)
            canvas.drawBitmap(bitmap, null, RectF(MARGIN.toFloat(), y, MARGIN + width, y + height), paint)
            y += height + 14
        } finally { bitmap.recycle() }
    }
    companion object {
        private const val WIDTH = 595
        private const val HEIGHT = 842
        private const val MARGIN = 42
        private const val BOTTOM = HEIGHT - MARGIN
    }
}
