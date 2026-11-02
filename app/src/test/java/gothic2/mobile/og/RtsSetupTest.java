package gothic2.mobile.og;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RtsSetupTest {
    @Test
    public void defaultsArePaladinsCommanderNormal() {
        RtsSetup s = new RtsSetup(new MemoryPrefs());
        // Ключи читает движок (RtsGame::readSetup): переименование здесь сломает выбор
        assertEquals("faction=paladins\nmode=commander\ndifficulty=1\nllm=0\n", s.fileText());
    }

    @Test
    public void cycleWrapsEveryOption() {
        RtsSetup s = new RtsSetup(new MemoryPrefs());
        s.cycleFaction();
        assertTrue(s.fileText().contains("faction=mercs\n"));
        s.cycleFaction();
        assertTrue(s.fileText().contains("faction=orcs\n"));
        s.cycleFaction();
        assertTrue(s.fileText().contains("faction=paladins\n"));
        s.cycleMode();
        assertTrue(s.fileText().contains("mode=observer\n"));
        s.cycleMode();
        assertTrue(s.fileText().contains("mode=hero\n"));
        s.cycleDifficulty();
        assertTrue(s.fileText().contains("difficulty=2\n"));
        s.cycleDifficulty();
        assertTrue(s.fileText().contains("difficulty=0\n"));
        s.toggleLlm();
        assertTrue(s.fileText().contains("llm=1\n"));
        assertEquals("Сторона: Паладины и ополчение", s.factionLabel());
    }
}
