package com.cenk.valocase.catalog.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Runs the catalog import once at startup, only when
 * {@code valocase.catalog.import-on-startup=true}. The default (false/absent)
 * means this bean is not created, so import never runs automatically — safe for
 * production. Any failure is logged; the application keeps running and, because
 * the import is transactional, no partial data is written.
 */
@Component
@ConditionalOnProperty(name = "valocase.catalog.import-on-startup", havingValue = "true")
@RequiredArgsConstructor
public class CatalogImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportRunner.class);

    private final CatalogImportService catalogImportService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("valocase.catalog.import-on-startup=true -> running catalog import");
        try {
            catalogImportService.importFromClasspath().ifPresentOrElse(
                    result -> log.info("Catalog import succeeded: {}", result),
                    () -> log.warn("Catalog import did not run (catalog JSON files not found)."));
        } catch (CatalogImportException e) {
            log.error("Catalog import failed; no changes were applied. Reason: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during catalog import; no changes were applied.", e);
        }
    }
}
