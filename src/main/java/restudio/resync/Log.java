package restudio.resync;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class Log {
    private static Logger logger;

    private Log() {
    }

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    public static void setLevel(String levelName) {
        if (logger == null || levelName == null) {
            return;
        }
        Level level = switch (levelName.toLowerCase()) {
            case "off" -> Level.OFF;
            case "severe", "error" -> Level.SEVERE;
            case "warn", "warning" -> Level.WARNING;
            case "fine", "debug" -> Level.FINE;
            case "finer" -> Level.FINER;
            case "finest", "trace" -> Level.FINEST;
            case "all" -> Level.ALL;
            default -> Level.INFO;
        };
        logger.setLevel(level);
        for (var handler : logger.getParent().getHandlers()) {
            handler.setLevel(level);
        }
    }

    public static void info(String msg) {
        logger.info(msg);
    }

    public static void warn(String msg) {
        logger.warning(msg);
    }

    public static void error(String msg) {
        logger.severe(msg);
    }

    public static void fine(String msg) {
        logger.fine(msg);
    }
}
