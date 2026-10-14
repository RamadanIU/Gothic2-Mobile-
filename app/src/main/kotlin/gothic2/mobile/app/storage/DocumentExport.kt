package gothic2.mobile.app.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream

/**
 * Запись файла, выбранного через «Сохранить как» (SAF). Системный диалог создаёт документ сразу,
 * поэтому при долгой подготовке (отчёт о сборке считает хэши гигабайтов) или ошибке раньше
 * оставался пустой файл. Теперь содержимое готовится во временном файле, копируется целиком,
 * а при ошибке созданный документ удаляется.
 */
object DocumentExport {
    fun write(resolver: ContentResolver, target: Uri, tempDir: File, produce: (OutputStream) -> Unit) {
        val tmp = File.createTempFile("export", ".tmp", tempDir)
        try {
            tmp.outputStream().use(produce)
            val out = resolver.openOutputStream(target, "wt") ?: error("Не удалось открыть файл для записи")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw e
        } finally {
            tmp.delete()
        }
    }
}
