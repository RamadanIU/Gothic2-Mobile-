package gothic2.mobile.app.storage

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.ui.gothic.Gothic

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
                result.onFailure { Gothic.toast(activity, activity.getString(R.string.diagnostics_failed, it.message), long = true) }
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".winlator.FileProvider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "Gothic II — журнал диагностики")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.diagnostics_send)))
                }
            }
        }
    }
}
