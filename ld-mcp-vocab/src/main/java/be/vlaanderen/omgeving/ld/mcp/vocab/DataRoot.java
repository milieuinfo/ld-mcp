package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The directory that {@code validate_file} may read from (the project root). A path from the model
 * is resolved against it, symbolic links included, and refused if it ends up outside: the tool
 * should not become a way to read, or echo in parse errors, arbitrary files on the system.
 */
final class DataRoot {

    private final Path root;

    /**
     * Creates a data root.
     *
     * @param root the directory
     * @throws IllegalArgumentException if it is not an existing directory
     */
    DataRoot(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("--data-root: not a directory: " + root.toAbsolutePath());
        }
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("--data-root: cannot be read: " + root.toAbsolutePath(), e);
        }
    }

    /**
     * Resolves a path given by the model.
     *
     * @param given a path relative to the data root, or absolute
     * @return the file to read, absolute
     * @throws IllegalArgumentException if the file does not exist, or lies outside the data root
     */
    Path resolve(String given) {
        Path path = root.resolve(given).normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("File cannot be read: " + path, e);
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("Refused: " + given + " lies outside the data root " + root
                    + " (start the server with --data-root to change it)");
        }
        return path;
    }
}
