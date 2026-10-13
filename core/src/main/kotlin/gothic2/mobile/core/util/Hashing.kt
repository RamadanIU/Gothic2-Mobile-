package gothic2.mobile.core.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Hashing {
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(path: Path): String = Files.newInputStream(path).use { sha256(it) }

    fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    fun isSha256(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
