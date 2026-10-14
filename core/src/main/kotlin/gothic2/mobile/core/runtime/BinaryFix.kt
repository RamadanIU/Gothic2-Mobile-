package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Точечное исправление стороннего двоичного файла среды (раздел 6 плана). Применяется, только
 * если файл в точности тот, для которого оно составлено (размер и SHA-256), а по смещению стоят
 * ожидаемые байты; результат сверяется с SHA-256 исправленного файла и записывается атомарно.
 * Любой другой файл (другая версия, встроенная библиотека Wine) не трогается.
 */
class BinaryFix(
    val id: String,
    private val size: Long,
    private val originalSha256: String,
    private val fixedSha256: String,
    private val offset: Int,
    private val original: ByteArray,
    private val fixed: ByteArray,
) {
    enum class Result { FIXED, ALREADY_FIXED, NOT_APPLICABLE }

    init {
        require(original.size == fixed.size && original.isNotEmpty()) { "$id: правка должна сохранять длину" }
        require(offset >= 0 && offset + original.size <= size) { "$id: смещение вне файла" }
        require(Hashing.isSha256(originalSha256) && Hashing.isSha256(fixedSha256)) { "$id: SHA-256" }
    }

    fun apply(file: Path): Result {
        if (!Files.isRegularFile(file) || Files.size(file) != size) return Result.NOT_APPLICABLE
        val bytes = Files.readAllBytes(file)
        when (Hashing.sha256(bytes)) {
            fixedSha256 -> return Result.ALREADY_FIXED
            originalSha256 -> Unit
            else -> return Result.NOT_APPLICABLE
        }
        check(bytes.copyOfRange(offset, offset + original.size).contentEquals(original)) { "$id: неожиданные байты" }
        fixed.copyInto(bytes, offset)
        check(Hashing.sha256(bytes) == fixedSha256) { "$id: результат не совпал с ожидаемым" }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return Result.FIXED
    }

    companion object {
        /**
         * D7VK 1.11 (Winlator 11.2, `syswow64/ddraw.dll`): `DDraw7Interface::GetCaps` в ветке
         * `lpDDHELCaps != nullptr` снимает DDCAPS2_CANCALIBRATEGAMMA в структуре видеокарты, а не HEL.
         * D3DX7 внутри Gothic (`D3DXGetDeviceCount` → `CHelInfo::Initialize`) вызывает
         * `GetCaps(NULL, &hel)` → запись по адресу 8 («Access Violation» при запуске). В D7VK 2.x
         * исправлено. Здесь — байт ModRM в `and dword [ebx+8], 0xFFEFFFFF` по RVA 0x42CB6 (смещение
         * в файле 0x43CB6): ebx → esi, где лежит указатель на HEL. Длина инструкции та же, релокаций нет.
         */
        val D7VK_1_11_HEL_CAPS = BinaryFix(
            id = "d7vk-1.11-hel-caps",
            size = 9_043_982,
            originalSha256 = "0e232ec53155454644a5001b93ecaadb0bd5bbb2b453d4bf8d4abe66e991b444",
            fixedSha256 = "034b1a2f9d8f4fba6df87b0952334ea45dd87c69607787714370454bb862039e",
            offset = 0x43CB6,
            original = byteArrayOf(0x81.toByte(), 0x63, 0x08),
            fixed = byteArrayOf(0x81.toByte(), 0x66, 0x08),
        )
    }
}
