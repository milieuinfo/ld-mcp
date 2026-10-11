package be.vlaanderen.omgeving.ld.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simple parser for {@code --key value} arguments. A key may be repeated (e.g. several
 * {@code --ontologies}); a key without a value counts as a flag.
 */
public final class ServerArgs {

    private final Map<String, List<String>> values = new LinkedHashMap<>();

    private ServerArgs() {
    }

    /**
     * Parses the command-line arguments.
     *
     * @param args the arguments as passed to {@code main}
     * @return the parsed arguments
     * @throws IllegalArgumentException if an argument does not start with {@code --} and is not
     *                                  the value of the preceding key
     */
    public static ServerArgs parse(String[] args) {
        ServerArgs parsed = new ServerArgs();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            List<String> list = parsed.values.computeIfAbsent(key, k -> new ArrayList<>());
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                list.add(args[++i]);
            }
        }
        return parsed;
    }

    /**
     * Returns all values of a key.
     *
     * @param key the key without the leading {@code --}
     * @return all values given for the key, in order; empty if the key is absent
     */
    public List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    /**
     * Returns the first value of a key, for keys that are expected only once.
     *
     * @param key the key without the leading {@code --}
     * @return the first value given for the key, if any
     */
    public Optional<String> first(String key) {
        return all(key).stream().findFirst();
    }

    /**
     * Returns all values of a key that must be given at least once.
     *
     * @param key the key without the leading {@code --}
     * @return all values given for the key, in order
     * @throws IllegalArgumentException if no value was given for the key
     */
    public List<String> required(String key) {
        List<String> list = all(key);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Required argument missing: --" + key);
        }
        return list;
    }

    /**
     * Tells whether a key was given, for keys used as switches.
     *
     * @param key the key without the leading {@code --}
     * @return whether the key was given, with or without a value
     */
    public boolean flag(String key) {
        return values.containsKey(key);
    }
}
