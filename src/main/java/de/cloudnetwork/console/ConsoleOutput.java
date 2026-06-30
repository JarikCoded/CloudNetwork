package de.cloudnetwork.console;

import org.jline.reader.LineReader;

public final class ConsoleOutput {
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

    private static synchronized void print(boolean stderr, String message) {
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
}
