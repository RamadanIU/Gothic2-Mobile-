package gothic2.mobile.app.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoragePathsTest {
    private val root = "/storage/emulated/0"
    private val storage = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"

    @Test
    fun `folder in phone storage maps to a file path`() {
        assertEquals("$root/Games/Gothic II", StoragePaths.fromDocumentId(storage, "primary:Games/Gothic II", root))
        assertEquals("$root/Download/Gothic", StoragePaths.fromDocumentId(downloads, "raw:$root/Download/Gothic", root))
        assertEquals(root, StoragePaths.fromDocumentId(storage, "primary:", root))
    }

    @Test
    fun `sd card, other providers and unsafe paths are not mapped`() {
        assertNull(StoragePaths.fromDocumentId(storage, "1234-ABCD:Games/Gothic", root))
        assertNull(StoragePaths.fromDocumentId(downloads, "msf:42", root))
        assertNull(StoragePaths.fromDocumentId("com.google.android.apps.docs.storage", "primary:Gothic", root))
        assertNull(StoragePaths.fromDocumentId(storage, "primary:Games/../../data", root))
        assertNull(StoragePaths.fromDocumentId(storage, "primary:Games/C:Gothic", root))
    }
}
