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

    public static void info(String message) {
        print(false, message);
    }

    public static void error(String message) {
        print(true, message);
    }

    public static void logOnly(String message) {
        appendLogLine("INFO", message);
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
    }
}
