package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DataRootTest {

    @Test
    void defaultsToTheWorkingDirectory() {
        // Surefire runs in the module directory, which has a pom.xml.
        assertEquals(Path.of("pom.xml").toAbsolutePath(), new DataRoot(Path.of("")).resolve("pom.xml"));
        assertThrows(IllegalArgumentException.class, () -> new DataRoot(Path.of("")).resolve("../pom.xml"));
    }

    @Test
    void aRootThatIsNoDirectoryIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () -> new DataRoot(Path.of("pom.xml")));
        assertThrows(IllegalArgumentException.class, () -> new DataRoot(Path.of("no-such-dir")));
    }
}
