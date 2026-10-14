package gothic2.mobile.app.storage

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import gothic2.mobile.core.util.FileOps

/**
 * Путь файловой системы для папки, выбранной через системный выбор папки (SAF). Нужен, чтобы
 * запускать игру прямо из папки игрока: Wine работает с обычными путями, а не с content://.
 * Доступ по пути даёт разрешение на файлы (приложение собирается с targetSdk 28, как Winlator).
 */
object StoragePaths {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** Путь или null, если папка не во внутренней памяти телефона (карта памяти, облако, USB). */
    fun fromTreeUri(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return fromDocumentId(uri.authority, docId, Environment.getExternalStorageDirectory().path)
    }

    /**
     * @param primaryRoot корень внутренней памяти (`/storage/emulated/0`)
     */
    fun fromDocumentId(authority: String?, docId: String, primaryRoot: String): String? {
        val rel = when (authority) {
            // «primary:Games/Gothic II». Карта памяти («1234-ABCD:…») не подходит: писать туда
            // напрямую Android приложениям не даёт, а Gothic пишет настройки и сохранения.
            EXTERNAL_STORAGE -> docId.takeIf { it.startsWith("primary:") }?.substringAfter(':')
            // Подпапка «Загрузок», открытая через раздел «Загрузки»: «raw:/storage/emulated/0/Download/…».
            DOWNLOADS -> docId.takeIf { it.startsWith("raw:$primaryRoot/") }?.removePrefix("raw:$primaryRoot/")
            else -> null
        }?.trim('/') ?: return null
        if (rel.isEmpty()) return primaryRoot
        // Двоеточие — разделитель дисков в настройках контейнера Winlator.
        if (!FileOps.isSafeRelative(rel) || rel.contains(':')) return null
        return "$primaryRoot/$rel"
    }
}
