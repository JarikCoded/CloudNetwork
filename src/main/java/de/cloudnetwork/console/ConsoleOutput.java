package de.cloudnetwork.console;

import org.jline.reader.LineReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ConsoleOutput {
    private static final Path LOG_DIR = Path.of("logs");
    private static final Path LOG_FILE = LOG_DIR.resolve("cloudnetwork.log");
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile LineReader lineReader;
    /** Optional listener that receives every log line (level + "|" + message). */
    private static volatile java.util.function.Consumer<String> logListener;

    private ConsoleOutput() {
    }

    public static void attachLineReader(LineReader reader) {
        lineReader = reader;
    }

    public static void detachLineReader(LineReader reader) {
        if (reader == null || lineReader == reader) {
            lineReader = null;
        }
    }

    /**
     * Registers a listener that is called for every log line (both INFO and ERROR).
     * The argument passed to the listener is {@code level + "|" + message}.
     * Pass {@code null} to remove the listener.
     */
    public static void setLogListener(java.util.function.Consumer<String> listener) {
        logListener = listener;
    }

    public static void info(String message) {
        print(false, message);
    }

    public static void error(String message) {
        print(true, message);
    }

    public static void logOnly(String message) {
        appendLogLine("INFO", message);
    }

    public static void logException(Throwable t) {
        if (t == null) return;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        for (String line : sw.toString().split("\\r?\\n", -1)) {
            appendLogLine("ERROR", line);
        }
    }

    private static synchronized void print(boolean stderr, String message) {
        appendLogLine(stderr ? "ERROR" : "INFO", message);
        LineReader reader = lineReader;
        if (reader != null) {
            reader.printAbove(message);
            return;
        }
        if (stderr) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
    }

    private static void appendLogLine(String level, String message) {
        String safeMessage = message == null ? "" : message;
        String line = "[" + LocalDateTime.now().format(LOG_TIMESTAMP) + "] [" + level + "] " + safeMessage + System.lineSeparator();
        try {
            Files.createDirectories(LOG_DIR);
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        java.util.function.Consumer<String> listener = logListener;
        if (listener != null) {
            try {
                listener.accept(level + "|" + safeMessage);
            } catch (Exception ignored) {
            }
        }
    }
}
