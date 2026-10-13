package be.vlaanderen.omgeving.ld.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Strict parser for {@code --key value} arguments. Every key must be declared, with its kind: a
 * typing error such as {@code --shape} for {@code --shapes} must stop the server, not silently
 * switch a step off.
 */
public final class ServerArgs {

    /** How a key takes values. */
    public enum Kind {
        /** No value; present or not. */
        FLAG,
        /** Exactly one value, at most once. */
        SINGLE,
        /** One value per occurrence, as often as needed. */
        REPEATABLE
    }

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    private ServerArgs() {
    }

    /**
     * Parses the command-line arguments.
     *
     * @param args  the arguments as passed to {@code main}
     * @param known the keys (without {@code --}) and their kinds
     * @return the parsed arguments
     * @throws IllegalArgumentException if a key is unknown, a value has no key, a flag has a value,
     *                                  a key that needs a value has none, or a single-valued key is
     *                                  given twice
     */
    public static ServerArgs parse(String[] args, Map<String, Kind> known) {
        return parse(args, known, false);
    }

    /**
     * Parses the command-line arguments, optionally with positional arguments (e.g. the files of a
     * command): every argument that is neither a key nor the value of the preceding key.
     *
     * @param args       the arguments
     * @param known      the keys (without {@code --}) and their kinds
     * @param positional whether positional arguments are allowed; see {@link #positional()}
     * @return the parsed arguments
     * @throws IllegalArgumentException as {@link #parse(String[], Map)}, and for a positional
     *                                  argument when none are allowed
     */
    public static ServerArgs parse(String[] args, Map<String, Kind> known, boolean positional) {
        ServerArgs parsed = new ServerArgs();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                if (!positional) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                parsed.positional.add(arg);
                continue;
            }
            String key = arg.substring(2);
            Kind kind = known.get(key);
            if (kind == null) {
                throw new IllegalArgumentException("Unknown argument: " + arg + ". Known: "
                        + String.join(", ", new TreeSet<>(known.keySet()).stream().map(k -> "--" + k).toList()));
            }
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            List<String> list = parsed.values.computeIfAbsent(key, k -> new ArrayList<>());
            switch (kind) {
                case FLAG -> {
                    // With positional arguments, the token after a flag is one of them.
                    if (hasValue && !positional) {
                        throw new IllegalArgumentException(arg + " takes no value, but got '" + args[i + 1] + "'");
                    }
                }
                case SINGLE, REPEATABLE -> {
                    if (!hasValue) {
                        throw new IllegalArgumentException(arg + " needs a value");
                    }
                    if (kind == Kind.SINGLE && !list.isEmpty()) {
                        throw new IllegalArgumentException(arg + " may be given only once");
                    }
                    list.add(args[++i]);
                }
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
     * Returns the value of a single-valued key.
     *
     * @param key the key without the leading {@code --}
     * @return the value given for the key, if any
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
     * Returns the positional arguments.
     *
     * @return the arguments that were neither a key nor a value, in order
     */
    public List<String> positional() {
        return List.copyOf(positional);
    }

    /**
     * Tells whether a key was given, for keys used as switches.
     *
     * @param key the key without the leading {@code --}
     * @return whether the key was given
     */
    public boolean flag(String key) {
        return values.containsKey(key);
    }
}
