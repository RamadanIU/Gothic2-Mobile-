package gothic2.mobile.og;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class GameFolderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File gameAt(String relative) {
        File root = new File(tmp.getRoot(), relative);
        assertTrue(new File(root, "_work/Data/Scripts/_compiled").mkdirs());
        assertTrue(new File(root, "Data").mkdirs());
        assertTrue(new File(root, "System").mkdirs());
        return root;
    }

    @Test
    public void gameDirIgnoresCase() {
        File root = new File(tmp.getRoot(), "G2");
        assertTrue(new File(root, "_WORK/DATA/scripts/_COMPILED").mkdirs());
        assertTrue(new File(root, "data").mkdirs());
        assertTrue(GameFolder.isGameDir(root));
    }

    @Test
    public void notGameDirWithoutCompiledScripts() {
        File root = new File(tmp.getRoot(), "half");
        assertTrue(new File(root, "Data").mkdirs());
        assertTrue(new File(root, "_work/Data").mkdirs());
        assertFalse(GameFolder.isGameDir(root));
    }

    @Test
    public void findsGameFewLevelsDown() {
        File game = gameAt("Games/GOG/Gothic II");
        assertEquals(game, GameFolder.findGameDir(tmp.getRoot()));
    }

    @Test
    public void skipsAndroidFolderAtRoot() {
        gameAt("Android/data/some.app/Gothic");
        assertNull(GameFolder.findGameDir(tmp.getRoot()));
    }

    @Test
    public void docIdToPath() {
        String root = "/storage/emulated/0";
        assertEquals("/storage/emulated/0/Games/Gothic II", GameFolder.docIdToPath("primary:Games/Gothic II", root));
        assertEquals("/storage/emulated/0", GameFolder.docIdToPath("primary:", root));
        assertEquals("/storage/1A2B-3C4D/Gothic", GameFolder.docIdToPath("1A2B-3C4D:Gothic", root));
        assertEquals("/storage/emulated/0/Download/G2", GameFolder.docIdToPath("raw:/storage/emulated/0/Download/G2", root));
        assertNull(GameFolder.docIdToPath("msf:123", root));
        assertNull(GameFolder.docIdToPath("без двоеточия", root));
    }
}
