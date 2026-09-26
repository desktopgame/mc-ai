package local.mcai;

import org.junit.Test;
import static org.junit.Assert.*;

public class DebugCommandTest {
    @Test public void acceptsOnlySupportedCommands() {
        for (String type : new String[] {"spawn", "follow", "stop", "look", "pickup", "deposit", "status", "help"}) {
            assertEquals(type, DebugCommand.parse("!agent " + type).type);
        }
    }
    @Test public void preservesSpeechWithoutInterpretingCommands() {
        assertEquals("こんにちは /kill", DebugCommand.parse("!agent say こんにちは /kill").text);
    }
    @Test public void rejectsUnknownAndMissingParameters() {
        for (String input : new String[] {"", "!agent", "!agent shell", "!agent goto 1 2 3",
                "!agent follow other-player", "!agent stop extra", "!agent say", "!agent say   ",
                "!agent say hello\nworld", "!agent say \u00a7cRed", "!agents spawn"}) {
            try { DebugCommand.parse(input); fail("Accepted " + input); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void rejectsOversizedSpeech() {
        try { DebugCommand.parse("!agent say " + new String(new char[257]).replace('\0', 'a')); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
