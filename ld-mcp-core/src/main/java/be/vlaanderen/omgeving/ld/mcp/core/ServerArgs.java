package be.vlaanderen.omgeving.ld.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eenvoudige parser voor {@code --sleutel waarde}-argumenten. Een sleutel mag herhaald worden
 * (bv. meerdere {@code --ontologies}); een sleutel zonder waarde geldt als vlag.
 */
public final class ServerArgs {

    private final Map<String, List<String>> values = new LinkedHashMap<>();

    private ServerArgs() {
    }

    public static ServerArgs parse(String[] args) {
        ServerArgs parsed = new ServerArgs();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Onverwacht argument: " + arg);
            }
            String key = arg.substring(2);
            List<String> list = parsed.values.computeIfAbsent(key, k -> new ArrayList<>());
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                list.add(args[++i]);
            }
        }
        return parsed;
    }

    public List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    public Optional<String> first(String key) {
        return all(key).stream().findFirst();
    }

    public List<String> required(String key) {
        List<String> list = all(key);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Verplicht argument ontbreekt: --" + key);
        }
        return list;
    }

    public boolean flag(String key) {
        return values.containsKey(key);
    }
}
