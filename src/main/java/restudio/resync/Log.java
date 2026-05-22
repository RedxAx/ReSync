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

    private static Logger logger() {
        return logger != null ? logger : Logger.getLogger("ReSync");
    }

    private static String clean(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.startsWith("[ReSync] ") ? msg.substring("[ReSync] ".length()) : msg;
    }

    public static void setLevel(String levelName) {
        if (levelName == null) {
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
        Logger activeLogger = logger();
        activeLogger.setLevel(level);
        if (activeLogger.getParent() == null) {
            return;
        }
        for (var handler : activeLogger.getParent().getHandlers()) {
            handler.setLevel(level);
        }
    }

    public static void info(String msg) {
        logger().info(clean(msg));
    }

    public static void warn(String msg) {
        logger().warning(clean(msg));
    }

    public static void warn(String msg, Throwable throwable) {
        logger().log(Level.WARNING, clean(msg), throwable);
    }

    public static void error(String msg) {
        logger().severe(clean(msg));
    }

    public static void error(String msg, Throwable throwable) {
        logger().log(Level.SEVERE, clean(msg), throwable);
    }

    public static void fine(String msg) {
        logger().fine(clean(msg));
    }
}
