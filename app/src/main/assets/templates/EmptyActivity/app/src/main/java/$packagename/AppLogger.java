package $packagename;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple file-based logger for runtime diagnostics.
 * Logs are written to the project's logs/ directory and can be
 * read by the AI agent via the read_runtime_log tool.
 *
 * Usage:
 *   AppLogger.d("MyActivity", "Button clicked, loading data...");
 *   AppLogger.e("MyActivity", "Failed to parse JSON", exception);
 */
public class AppLogger {

    private static final long MAX_LOG_SIZE = 256 * 1024; // 256 KB
    private static final long MAX_CRASH_SIZE = 128 * 1024; // 128 KB
    private static final Object LOCK = new Object();
    private static File logDir;

    /** Called once at startup. Safe to call multiple times. */
    public static void init(File dir) {
        synchronized (LOCK) {
            logDir = dir;
            if (dir != null) {
                dir.mkdirs();
            }
        }
    }

    /** Returns the current log directory, or null if not initialized. */
    public static File getLogDir() {
        synchronized (LOCK) {
            return logDir;
        }
    }

    public static void d(String tag, String message) {
        writeLog("D", tag, message, null);
    }

    public static void i(String tag, String message) {
        writeLog("I", tag, message, null);
    }

    public static void w(String tag, String message) {
        writeLog("W", tag, message, null);
    }

    public static void e(String tag, String message) {
        writeLog("E", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        writeLog("E", tag, message, throwable);
    }

    /** Write a crash stack trace to crash.log. */
    public static void crash(Throwable throwable) {
        synchronized (LOCK) {
            if (logDir == null) return;
            try {
                File crashFile = new File(logDir, "crash.log");
                rotateIfNeeded(crashFile, MAX_CRASH_SIZE);
                FileWriter fw = new FileWriter(crashFile, true);
                fw.write("--- CRASH " + timestamp() + " ---\n");
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                fw.write(sw.toString());
                fw.write("\n");
                fw.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeLog(String level, String tag, String message, Throwable t) {
        synchronized (LOCK) {
            if (logDir == null) return;
            try {
                File logFile = new File(logDir, "app.log");
                rotateIfNeeded(logFile, MAX_LOG_SIZE);
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(timestamp() + " " + level + "/" + tag + ": " + message + "\n");
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    fw.write(sw.toString());
                }
                fw.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void rotateIfNeeded(File file, long maxSize) {
        if (file.exists() && file.length() > maxSize) {
            File backup = new File(file.getPath() + ".1");
            backup.delete();
            file.renameTo(backup);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
