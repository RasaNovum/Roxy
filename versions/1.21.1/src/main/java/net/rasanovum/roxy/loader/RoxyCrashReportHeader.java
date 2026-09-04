package net.rasanovum.roxy.loader;

import net.neoforged.fml.CrashReportCallables;

import java.nio.file.Path;

public final class RoxyCrashReportHeader {
    private static final String PREFIX = "roxy.crash_header.";
    private static final String REGISTERED = PREFIX + "registered";
    private static final String FILE = PREFIX + "voxy_file";
    private static final String STATE = PREFIX + "voxy_state";
    private static final String FAILURE = PREFIX + "voxy_failure";

    private RoxyCrashReportHeader() {
    }

    public static void register() {
        try {
            synchronized (System.getProperties()) {
                if (Boolean.parseBoolean(System.getProperty(REGISTERED))) return;
                System.setProperty(REGISTERED, Boolean.TRUE.toString());
                try {
                    CrashReportCallables.registerHeader(RoxyCrashReportHeader::getHeader);
                } catch (Throwable throwable) {
                    System.clearProperty(REGISTERED);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void searching() {
        advance("SEARCHING", 0, null, null);
    }

    public static void notFound() {
        advance("NOT_FOUND", 1, null, null);
    }

    public static void located(Path path) {
        advance("LOCATED", 2, path, null);
    }

    public static void patching(Path path) {
        advance("PATCHING", 3, path, null);
    }

    public static void patched(Path path) {
        advance("PATCHED", 4, path, null);
    }

    public static void initializing() {
        advance("INITIALIZING", 5, null, null);
    }

    public static void initialized() {
        advance("SUCCESS", 6, null, null);
    }

    public static void failed(Throwable throwable) {
        advance("FAILED", 7, null, throwable);
    }

    private static void advance(String state, int rank, Path path, Throwable failure) {
        try {
            synchronized (System.getProperties()) {
                String current = System.getProperty(STATE, "SEARCHING");
                if (current.equals("FAILED") || current.equals("SUCCESS")) return;
                if (rank < rank(current)) return;
                if (path != null && path.getFileName() != null) {
                    String name = path.getFileName().toString();
                    if (!name.startsWith("roxy-patched-voxy-")) System.setProperty(FILE, sanitize(name, 180));
                }
                System.setProperty(STATE, state);
                if (failure != null) {
                    String detail = failure.getClass().getSimpleName();
                    if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                        detail += ": " + failure.getMessage();
                    }
                    System.setProperty(FAILURE, sanitize(detail, 240));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int rank(String state) {
        return switch (state) {
            case "NOT_FOUND" -> 1;
            case "LOCATED" -> 2;
            case "PATCHING" -> 3;
            case "PATCHED" -> 4;
            case "INITIALIZING" -> 5;
            case "SUCCESS" -> 6;
            case "FAILED" -> 7;
            default -> 0;
        };
    }

    private static String getHeader() {
        try {
            String state = System.getProperty(STATE, "SEARCHING");
            String file = System.getProperty(FILE, "the configured Voxy jar");
            String status = switch (state) {
                case "SUCCESS" -> "Roxy is present! It loaded " + file + " successfully in this run.";
                case "FAILED" -> "Roxy is present! It attempted to load " + file + ", but loading failed in this run.";
                case "NOT_FOUND" -> "Roxy is present! It did not locate a Voxy jar before this crash.";
                default -> "Roxy is present! It found " + file + ", but loading had not completed before this crash.";
            };
            if (state.equals("FAILED")) {
                String failure = System.getProperty(FAILURE);
                if (failure != null && !failure.isBlank()) status += "\nLoading failure: " + sanitize(failure, 240);
            }
            return status
                    + "\nPlease do NOT report this issue to the original Voxy developer; they are not responsible for Roxy and cannot provide support for it. Instead, report issues to Roxy's issue page: https://github.com/RasaNovum/Roxy/issues"
                    + "\nIf this crash appears Voxy-related, reproduce it with the smallest practical mod set to distinguish a core issue from a mod incompatibility.\n";
        } catch (Throwable ignored) {
            return "Roxy is present. Please do NOT report Roxy-related issues to the original Voxy developer. Instead, report issues to Roxy's issue page: https://github.com/RasaNovum/Roxy/issues \n";
        }
    }

    private static String sanitize(String value, int limit) {
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
    }
}
