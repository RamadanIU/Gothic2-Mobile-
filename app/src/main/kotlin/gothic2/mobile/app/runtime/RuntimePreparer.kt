package gothic2.mobile.app.runtime

import android.content.res.AssetManager
import gothic2.mobile.app.storage.Diagnostics
import gothic2.mobile.core.runtime.ComponentInstaller
import gothic2.mobile.core.runtime.RuntimeComponents
import java.io.FileNotFoundException
import java.nio.file.Path

/**
 * Распаковка закреплённых компонентов среды из assets во внутреннее хранилище
 * (раздел 5 плана). Сейчас архивов в APK нет — они появятся после выбора версий на
 * этапе 1; до этого подготовка честно сообщает, каких компонентов не хватает.
 */
class RuntimePreparer(
    private val assets: AssetManager,
    installRoot: Path,
    private val diagnostics: Diagnostics,
    private val allowUnpinned: Boolean,
) {
    private val installer = ComponentInstaller(installRoot, { c -> assets.open(c.archive) })

    data class Report(val installed: List<String>, val missing: List<String>)

    fun prepare(components: RuntimeComponents): Report {
        val installed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (id in RuntimeComponents.REQUIRED) {
            val c = components[id]
            if (c == null) {
                missing += id
                continue
            }
            if (installer.isInstalled(c)) {
                installed += id
                continue
            }
            try {
                installer.install(c, allowUnpinned)
                installed += id
                diagnostics.log("runtime", "installed ${c.id} ${c.version}")
            } catch (e: FileNotFoundException) {
                missing += id
            }
        }
        if (missing.isNotEmpty()) diagnostics.log("runtime", "missing components: $missing")
        return Report(installed, missing)
    }
}
