package com.cenk.valocase.catalog.importer;

/**
 * Summary of a successful catalog import, for logging.
 */
public record CatalogImportResult(
        int skinsUpserted,
        int casesUpserted,
        int caseEntriesInserted,
        int sampleRowsNeutralized
) {
}
