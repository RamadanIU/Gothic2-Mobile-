package gothic2.mobile.app.storage

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import gothic2.mobile.app.app

/**
 * Отправка журнала диагностики через системное «Поделиться» (мессенджер, почта, файлы) — проще и
 * надёжнее, чем сохранение через выбор документа. Файл лежит в приватном каталоге и открывается
 * получателю через FileProvider (тот, что объявлен модулем Winlator, — он покрывает files/).
 */
object DiagnosticsShare {
    fun share(activity: Activity) {
        val app = activity.app
        app.background.execute {
            val result = runCatching {
                app.diagnostics.exportToFile(activity.filesDir.toPath().resolve("share/gothic2-diagnostics.txt")).toFile()
            }
            activity.runOnUiThread {
                result.onFailure { Toast.makeText(activity, "Не удалось подготовить журнал: ${it.message}", Toast.LENGTH_LONG).show() }
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".winlator.FileProvider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "Gothic II Mobile — журнал диагностики")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(send, "Отправить журнал"))
                }
            }
        }
    }
}
