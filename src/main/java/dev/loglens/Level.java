package dev.loglens;

/** Normalised log severity. Ordinal order is severity order. */
enum Level {
    UNKNOWN, TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

    /** Maps a level token from any common Java logging framework to a normalised level. */
    static Level parse(String token) {
        if (token == null) return UNKNOWN;
        switch (token.toUpperCase()) {
            case "TRACE": case "FINEST": case "FINER": return TRACE;
            case "DEBUG": case "FINE": case "CONFIG": return DEBUG;
            case "INFO": case "NOTICE": return INFO;
            case "WARN": case "WARNING": return WARN;
            case "ERROR": case "SEVERE": case "ERR": return ERROR;
            case "FATAL": case "CRITICAL": return FATAL;
            default: return UNKNOWN;
        }
    }

    int color() {
        switch (this) {
            case TRACE: return Style.SLATE;
            case DEBUG: return Style.BLUE;
            case INFO: return Style.GREEN;
            case WARN: return Style.AMBER;
            case ERROR: return Style.RED;
            case FATAL: return Style.MAGENTA;
            default: return Style.MUTED;
        }
    }
}
