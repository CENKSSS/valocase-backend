package com.cenk.valocase.catalog.importer;

/**
 * Thrown when catalog JSON fails to parse or fails validation. Aborts the
 * import before any rows are written (no partial import).
 */
public class CatalogImportException extends RuntimeException {

    public CatalogImportException(String message) {
        super(message);
    }

    public CatalogImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
