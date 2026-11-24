package dev.copperworks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Сверяет код и messages.yml без запуска сервера: исходники читаются как текст.
 * <ul>
 *   <li>каждый ключ, который код передаёт в Messages (get/send/actionBar/raw), есть в messages.yml;</li>
 *   <li>каждый плейсхолдер &lt;name&gt; в тексте передаётся кодом, а при прямом вызове с ключом-литералом —
 *       и наоборот, всё переданное используется в тексте;</li>
 *   <li>в messages.yml нет неиспользуемых ключей;</li>
 *   <li>в YAML нет ключей, которые Bukkit прочитает как boolean (yes/no/on/off…).</li>
 * </ul>
 * Ключ может быть литералом, тернарником из литералов, конкатенацией ("job." + job.id() + ".name"),
 * параметром метода (берутся литералы из вызовов этого метода в том же файле) или локальной переменной.
 */
class MessagesConsistencyTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path MESSAGES = Path.of("src/main/resources/messages.yml");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    /** Ключи, которые строятся методом, а не строкой: выражение → регулярка по ключам. */
    private static final Map<Pattern, String> KEY_METHODS = Map.of(
        Pattern.compile("\\.messageKey\\(\\)$"), "status\\.[a-z0-9_-]+");

    private static final Set<String> MINI_MESSAGE_TAGS = Set.of(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
        "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
        "color", "colour", "c", "bold", "b", "italic", "em", "i", "underlined", "u", "strikethrough", "st",
        "obfuscated", "obf", "reset", "click", "hover", "key", "keybind", "lang", "tr", "translate", "lang_or",
        "tr_or", "translate_or", "insertion", "insert", "rainbow", "gradient", "transition", "font", "newline",
        "br", "selector", "sel", "score", "nbt", "data", "pride", "shadow", "sprite", "head");
    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do", "yield",
        "throw", "case");
    private static final Set<String> YAML_BOOLEANS = Set.of(
        "yes", "no", "on", "off", "true", "false", "y", "n");

    private static final Pattern CALL = Pattern.compile(
        "(?<![\\w])(?:m\\(\\)|messages\\(\\)|messages|m)\\s*\\.\\s*(get|send|actionBar|raw)\\s*\\(");
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "Placeholder\\.(?:unparsed|parsed|component|styling)\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern METHOD = Pattern.compile(
        "(?:(?:public|private|protected|static|final|synchronized|default)\\s+)*"
            + "([\\w.<>\\[\\]?@]+(?:\\s*<[^;{}()]*>)?)\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TAG = Pattern.compile("<([!/#?]?)([a-z0-9_-]+)(?::[^>]*)?>");
    private static final Pattern KEY_LIKE = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)*");

    private static Map<String, String> messages;
    private static List<SourceFile> files;
    private static List<Call> calls;

    record SourceFile(Path path, String code, List<Method> methods) {
        int line(int offset) {
            int line = 1;
            for (int i = 0; i < offset && i < code.length(); i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        String where(int offset) {
            return SOURCES.relativize(path) + ":" + line(offset);
        }
    }

    record Method(String returnType, String name, List<String> params, int declStart, int bodyStart, int bodyEnd) {
    }

    /** Вызов Messages: какие ключи он может использовать и какие плейсхолдеры передаёт. */
    record Call(String where, String keyExpr, Set<String> keys, Set<String> placeholders, boolean direct) {
    }

    @BeforeAll
    static void load() throws IOException {
        messages = new LinkedHashMap<>();
        flatten("", loadYaml(MESSAGES), messages);
        files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
                files.add(new SourceFile(path, code, methods(code)));
            }
        }
        calls = new ArrayList<>();
        for (SourceFile file : files) {
            Matcher m = CALL.matcher(file.code());
            while (m.find()) {
                int open = m.end() - 1;
                int close = matching(file.code(), open);
                List<String> args = splitArgs(file.code().substring(open + 1, close));
                int keyIndex = m.group(1).equals("send") || m.group(1).equals("actionBar") ? 1 : 0;
                if (args.size() <= keyIndex) {
                    continue;
                }
                String keyExpr = args.get(keyIndex).strip();
                Set<String> keys = resolve(file, open, keyExpr, 0);
                boolean direct = STRING.matcher(keyExpr).matches();
                calls.add(new Call(file.where(m.start()), keyExpr, keys,
                    providedPlaceholders(file, open, close), direct));
            }
        }
    }

    @Test
    void everyKeyFromCodeExists() {
        List<String> problems = new ArrayList<>();
        for (Call call : calls) {
            if (call.keys().isEmpty()) {
                problems.add(call.where() + ": не удалось определить ключ из выражения «" + call.keyExpr()
                    + "» (литерал, тернарник, конкатенация, параметр или локальная переменная)");
            }
            for (String key : call.keys()) {
                if (!messages.containsKey(key)) {
                    problems.add(call.where() + ": ключа «" + key + "» нет в messages.yml");
                }
            }
        }
        // Литералы, похожие на ключи сообщений (первый сегмент совпадает с разделом messages.yml).
        Set<String> sections = new TreeSet<>();
        messages.keySet().forEach(key -> sections.add(key.split("\\.")[0]));
        for (SourceFile file : files) {
            Matcher m = STRING.matcher(file.code());
            while (m.find()) {
                String value = m.group(1);
                if (value.contains(".") && KEY_LIKE.matcher(value).matches()
                    && sections.contains(value.split("\\.")[0]) && !messages.containsKey(value)) {
                    problems.add(file.where(m.start()) + ": строка «" + value + "» похожа на ключ, но его нет в messages.yml");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "Ключи сообщений:\n" + String.join("\n", problems));
    }

    @Test
    void placeholdersMatch() {
        List<String> problems = new ArrayList<>();
        for (Call call : calls) {
            for (String name : call.placeholders()) {
                if (!name.matches("[!?#]?[a-z0-9_-]+")) {
                    problems.add(call.where() + ": недопустимое имя плейсхолдера «" + name + "»");
                }
            }
            for (String key : call.keys()) {
                String text = messages.get(key);
                if (text == null) {
                    continue;
                }
                Set<String> used = tags(text);
                for (String tag : used) {
                    if (!call.placeholders().contains(tag)) {
                        problems.add(call.where() + ": «" + key + "» использует <" + tag + ">, а код его не передаёт"
                            + " (передаёт " + call.placeholders() + ")");
                    }
                }
                if (call.direct()) {
                    for (String name : call.placeholders()) {
                        if (!used.contains(name)) {
                            problems.add(call.where() + ": код передаёт <" + name + "> в «" + key
                                + "», но в тексте его нет");
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "Плейсхолдеры:\n" + String.join("\n", problems));
    }

    @Test
    void noUnusedMessages() {
        Set<String> referenced = new LinkedHashSet<>();
        calls.forEach(call -> referenced.addAll(call.keys()));
        for (SourceFile file : files) {
            Matcher m = STRING.matcher(file.code());
            while (m.find()) {
                if (messages.containsKey(m.group(1))) {
                    referenced.add(m.group(1));
                }
            }
        }
        List<String> unused = messages.keySet().stream().filter(key -> !referenced.contains(key)).toList();
        assertTrue(unused.isEmpty(), () -> "В messages.yml есть ключи, которые код не использует:\n"
            + String.join("\n", unused));
    }

    @Test
    void noBooleanLikeYamlKeys() throws IOException {
        for (Path path : List.of(MESSAGES, CONFIG)) {
            List<String> problems = new ArrayList<>();
            checkKeys("", loadYaml(path), problems);
            assertTrue(problems.isEmpty(), () -> path + ": Bukkit прочитает эти ключи как boolean, переименуй:\n"
                + String.join("\n", problems));
        }
    }

    @Test
    void parserSeesTheCode() {
        // Защита от «тихо зелёного» теста, если регулярки перестанут находить вызовы.
        assertTrue(calls.size() > 50, "найдено слишком мало вызовов Messages: " + calls.size());
        assertFalse(messages.isEmpty(), "messages.yml пуст");
    }

    // ── Разбор ключей ─────────────────────────────────────

    private static Set<String> resolve(SourceFile file, int at, String expr, int depth) {
        Set<String> keys = new LinkedHashSet<>();
        expr = expr.strip();
        if (depth > 4 || expr.isEmpty()) {
            return keys;
        }
        Matcher literal = STRING.matcher(expr);
        if (literal.matches()) {
            keys.add(literal.group(1));
            return keys;
        }
        for (Map.Entry<Pattern, String> method : KEY_METHODS.entrySet()) {
            if (method.getKey().matcher(expr).find()) {
                return matchKeys(method.getValue());
            }
        }
        List<String> parts = splitTopLevel(expr, '+');
        if (parts.size() > 1) {
            StringBuilder regex = new StringBuilder();
            for (String part : parts) {
                Matcher m = STRING.matcher(part.strip());
                regex.append(m.matches() ? Pattern.quote(m.group(1)) : "[a-z0-9_-]+");
            }
            return matchKeys(regex.toString());
        }
        if (expr.contains("?") && STRING.matcher(expr).find()) {
            Matcher m = STRING.matcher(expr);
            while (m.find()) {
                keys.add(m.group(1));
            }
            return keys;
        }
        if (expr.matches("\\w+")) {
            Method method = enclosing(file, at);
            if (method == null) {
                return keys;
            }
            int index = method.params().indexOf(expr);
            if (index >= 0) {
                Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(method.name()) + "\\s*\\(").matcher(file.code());
                while (m.find()) {
                    if (m.start() >= method.declStart() && m.start() < method.bodyStart()) {
                        continue; // это объявление
                    }
                    int open = m.end() - 1;
                    List<String> args = splitArgs(file.code().substring(open + 1, matching(file.code(), open)));
                    if (args.size() == method.params().size()) {
                        keys.addAll(resolve(file, open, args.get(index), depth + 1));
                    }
                }
                return keys;
            }
            String body = file.code().substring(method.bodyStart(), method.bodyEnd());
            Matcher local = Pattern.compile("(?s)\\b" + Pattern.quote(expr) + "\\s*=(?!=)\\s*(.*?);").matcher(body);
            if (local.find()) {
                return resolve(file, at, local.group(1), depth + 1);
            }
        }
        return keys;
    }

    private static Set<String> matchKeys(String regex) {
        Pattern pattern = Pattern.compile(regex);
        Set<String> keys = new LinkedHashSet<>();
        for (String key : messages.keySet()) {
            if (pattern.matcher(key).matches()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Плейсхолдеры вызова, без вложенных вызовов Messages; плюс помощники, возвращающие TagResolver. */
    private static Set<String> providedPlaceholders(SourceFile file, int open, int close) {
        String original = file.code().substring(open + 1, close);
        StringBuilder span = new StringBuilder(original);
        // Замена пробелами той же длины не сдвигает позиции, поэтому порядок не важен.
        Matcher nested = CALL.matcher(original);
        while (nested.find()) {
            int end = matching(original, nested.end() - 1) + 1;
            span.replace(nested.start(), end, " ".repeat(end - nested.start()));
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(span);
        while (m.find()) {
            names.add(m.group(1));
        }
        for (Method helper : file.methods()) {
            if (helper.returnType().startsWith("TagResolver")
                && Pattern.compile("(?<![\\w.])" + Pattern.quote(helper.name()) + "\\s*\\(").matcher(span).find()) {
                Matcher inner = PLACEHOLDER.matcher(file.code().substring(helper.bodyStart(), helper.bodyEnd()));
                while (inner.find()) {
                    names.add(inner.group(1));
                }
            }
        }
        return names;
    }

    private static Set<String> tags(String text) {
        Set<String> tags = new TreeSet<>();
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            if (m.group(1).isEmpty() && !MINI_MESSAGE_TAGS.contains(m.group(2))) {
                tags.add(m.group(2));
            }
        }
        return tags;
    }

    // ── Разбор Java как текста ────────────────────────────

    private static List<Method> methods(String code) {
        List<Method> methods = new ArrayList<>();
        Matcher m = METHOD.matcher(code);
        while (m.find()) {
            String name = m.group(2);
            if (JAVA_KEYWORDS.contains(name) || JAVA_KEYWORDS.contains(m.group(1))) {
                continue;
            }
            int bodyStart = m.end() - 1;
            List<String> params = new ArrayList<>();
            for (String param : splitParams(m.group(3))) {
                String[] words = param.strip().split("[\\s.]+");
                if (words.length > 0 && !words[words.length - 1].isEmpty()) {
                    params.add(words[words.length - 1]);
                }
            }
            methods.add(new Method(m.group(1).replaceAll("@\\w+\\s*", "").strip(), name, params, m.start(),
                bodyStart, matching(code, bodyStart)));
        }
        return methods;
    }

    private static Method enclosing(SourceFile file, int offset) {
        Method best = null;
        for (Method method : file.methods()) {
            if (method.bodyStart() < offset && offset < method.bodyEnd()
                && (best == null || method.bodyStart() > best.bodyStart())) {
                best = method;
            }
        }
        return best;
    }

    /** Индекс закрывающей скобки для открывающей в позиции open; строки и символы пропускаются. */
    private static int matching(CharSequence code, int open) {
        char opening = code.charAt(open);
        char closing = opening == '(' ? ')' : opening == '{' ? '}' : ']';
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(code, i);
            } else if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("Нет парной скобки для позиции " + open);
    }

    private static int skipQuoted(CharSequence code, int start) {
        char quote = code.charAt(start);
        for (int i = start + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return code.length();
    }

    /** Параметры объявления: запятые внутри дженериков (BiConsumer&lt;A, B&gt;) не разделяют. */
    private static List<String> splitParams(String params) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts.size() == 1 && parts.getFirst().isBlank() ? List.of() : parts;
    }

    private static List<String> splitArgs(String args) {
        List<String> parts = splitTopLevel(args, ',');
        return parts.size() == 1 && parts.getFirst().isBlank() ? List.of() : parts;
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(text, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** Заменяет комментарии пробелами (переводы строк сохраняются, чтобы номера строк совпадали). */
    private static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipQuoted(code, i);
                out.append(code, i, Math.min(end + 1, code.length()));
                i = end;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                if (i < code.length()) {
                    out.append('\n');
                }
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? code.length() : end + 2;
                for (int j = i; j < end; j++) {
                    out.append(code.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ── YAML ──────────────────────────────────────────────

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return root instanceof Map<?, ?> map ? map : Map.of();
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key + ".", child, out);
            } else {
                out.put(key, String.valueOf(entry.getValue()));
            }
        }
    }

    private static void checkKeys(String prefix, Map<?, ?> map, List<String> problems) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String name) || YAML_BOOLEANS.contains(name.toLowerCase(Locale.ROOT))) {
                problems.add(prefix + key);
            }
            if (entry.getValue() instanceof Map<?, ?> child) {
                checkKeys(prefix + key + ".", child, problems);
            }
        }
    }
}
