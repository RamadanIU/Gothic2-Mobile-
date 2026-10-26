package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.Hashing
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Замена стороннего файла среды своей сборкой из APK (раздел 6 плана), когда точечной правки
 * байтов ([BinaryFix]) мало. Заменяется только известный файл — один из [replaces] по SHA-256;
 * любой другой (другая версия, встроенная библиотека Wine) не трогается. Новый файл сверяется
 * с [sha256] до замены и записывается атомарно через временный файл рядом.
 */
class FileReplacement(
    val id: String,
    private val sha256: String,
    private val replaces: Set<String>,
) {
    enum class Result { INSTALLED, ALREADY_INSTALLED, NOT_APPLICABLE }

    init {
        require(Hashing.isSha256(sha256) && replaces.all(Hashing::isSha256)) { "$id: SHA-256" }
        require(sha256 !in replaces) { "$id: файл заменяет сам себя" }
    }

    fun apply(target: Path, source: () -> InputStream): Result {
        if (!Files.isRegularFile(target)) return Result.NOT_APPLICABLE
        when (Hashing.sha256(target)) {
            sha256 -> return Result.ALREADY_INSTALLED
            in replaces -> Unit
            else -> return Result.NOT_APPLICABLE
        }
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            source().use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            check(Hashing.sha256(tmp) == sha256) { "$id: файл в APK не совпал с ожидаемым" }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return Result.INSTALLED
    }

    companion object {
        /**
         * D7VK 1.11 из Winlator 11.2, собранный с нашим патчем (`third_party/d7vk.patch`,
         * `tools/build-d7vk.sh`, docs/PROGRESS.md, 2026-10-26):
         * - запись в любой mip-уровень текстуры помечает к выгрузке всю текстуру. Gothic заполняет
         *   уровни из потока загрузки ресурсов, каждый на своей поверхности, а D7VK выгружал текстуру
         *   на GPU только по флагу верхнего уровня: уровни, записанные позже, оставались пустыми —
         *   далёкие объекты (они берут маленькие уровни) рисовались чёрными;
         * - флаг снимается до чтения поверхностей: уровень, записанный во время выгрузки или занятый
         *   загрузчиком, после его `Unlock` снова помечает текстуру и выгрузится при следующем рисовании;
         * - то же исправление GetCaps для D3DX7, что и [BinaryFix.D7VK_1_11_HEL_CAPS].
         *
         * Заменяет исходный файл Winlator и файл после [BinaryFix.D7VK_1_11_HEL_CAPS].
         */
        val D7VK_1_11_GOTHIC = FileReplacement(
            id = "d7vk-1.11-gothic",
            sha256 = "4191ffb518efd4142c25fe8eb92b0ea3a2754c49e2c1d98b26bc216bde7f9751",
            replaces = setOf(
                "0e232ec53155454644a5001b93ecaadb0bd5bbb2b453d4bf8d4abe66e991b444",
                "034b1a2f9d8f4fba6df87b0952334ea45dd87c69607787714370454bb862039e",
            ),
        )
    }
}
