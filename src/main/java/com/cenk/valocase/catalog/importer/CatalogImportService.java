package com.cenk.valocase.catalog.importer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Reads Unity catalog JSON copied into the backend resources and upserts skins,
 * cases, and case entries. IDs are preserved exactly (no rename, no cleaning,
 * no generation). The whole import runs in one transaction: validation happens
 * before any write, so a failure means nothing is imported.
 */
@Service
@RequiredArgsConstructor
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

    static final String SKINS_RESOURCE = "catalog/skins.json";
    static final String CASES_RESOURCE = "catalog/cases.json";

    /** Sample IDs seeded by V3, neutralized on import. */
    static final List<String> SAMPLE_SKIN_IDS = List.of(
            "sample_skin_phantom_neon", "sample_skin_vandal_dragon", "sample_skin_classic_basic");
    static final List<String> SAMPLE_CASE_IDS = List.of("sample_case_starter");

    private final ObjectMapper objectMapper;
    private final SkinRepository skinRepository;
    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseEntryRepository caseEntryRepository;

    /**
     * Imports the catalog from classpath resources. Returns empty (no-op) if the
     * JSON files are not present, leaving existing DB data unchanged.
     *
     * @throws CatalogImportException if parsing or validation fails
     */
    @Transactional
    public Optional<CatalogImportResult> importFromClasspath() {
        ClassPathResource skinsRes = new ClassPathResource(SKINS_RESOURCE);
        ClassPathResource casesRes = new ClassPathResource(CASES_RESOURCE);

        if (!skinsRes.exists() || !casesRes.exists()) {
            log.warn("Catalog import skipped: missing{}{}. Existing DB data left unchanged.",
                    skinsRes.exists() ? "" : " classpath:" + SKINS_RESOURCE,
                    casesRes.exists() ? "" : " classpath:" + CASES_RESOURCE);
            return Optional.empty();
        }

        List<SkinCatalogEntry> skins = parseList(skinsRes, SKINS_RESOURCE, "skins",
                new TypeReference<List<SkinCatalogEntry>>() {});
        List<CaseCatalogEntry> cases = parseList(casesRes, CASES_RESOURCE, "cases",
                new TypeReference<List<CaseCatalogEntry>>() {});

        Map<String, List<PoolEntry>> pools = buildPools(cases);
        validate(skins, cases, pools);

        CatalogImportResult result = doImport(skins, cases, pools);
        log.info("Catalog import complete: {}", result);
        return Optional.of(result);
    }

    // ---- parsing -----------------------------------------------------------

    private <T> List<T> parseList(ClassPathResource resource, String name, String arrayKey,
                                  TypeReference<List<T>> type) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode array = locateArray(root, arrayKey, name);
            return objectMapper.convertValue(array, type);
        } catch (IOException | JacksonException e) {
            throw new CatalogImportException("Failed to read/parse " + name + ": " + e.getMessage(), e);
        }
    }

    private JsonNode locateArray(JsonNode root, String arrayKey, String name) {
        if (root != null && root.isArray()) {
            return root;
        }
        if (root != null && root.has(arrayKey) && root.get(arrayKey).isArray()) {
            return root.get(arrayKey);
        }
        throw new CatalogImportException(
                name + " must be a JSON array, or an object with an array field '" + arrayKey + "'");
    }

    /** Normalizes each case's manualDropPool, logging dedupe and empty-pool warnings. */
    private Map<String, List<PoolEntry>> buildPools(List<CaseCatalogEntry> cases) {
        Map<String, List<PoolEntry>> pools = new LinkedHashMap<>();
        for (CaseCatalogEntry c : cases) {
            if (c.getCaseId() == null || c.getCaseId().isBlank()) {
                continue; // reported as a hard error in validate()
            }
            List<PoolEntry> pool = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<Object> raw = c.getManualDropPool() == null ? List.of() : c.getManualDropPool();
            for (Object node : raw) {
                PoolEntry entry = toPoolEntry(node, c.getCaseId());
                if (!seen.add(entry.skinId())) {
                    log.warn("Case '{}' lists skinId '{}' more than once; collapsing to a single entry.",
                            c.getCaseId(), entry.skinId());
                    continue;
                }
                pool.add(entry);
            }
            if (pool.isEmpty()) {
                log.warn("Case '{}' has an empty drop pool.", c.getCaseId());
            }
            pools.put(c.getCaseId(), pool);
        }
        return pools;
    }

    private PoolEntry toPoolEntry(Object node, String caseId) {
        String skinId;
        int weight = 1;
        if (node instanceof String s) {
            skinId = s;
        } else if (node instanceof Map<?, ?> map) {
            Object skinIdValue = map.get("skinId");
            skinId = skinIdValue != null ? skinIdValue.toString() : null;
            Object weightValue = map.get("weight");
            if (weightValue instanceof Number number && number.intValue() > 0) {
                weight = number.intValue();
            }
        } else {
            throw new CatalogImportException(
                    "Case '" + caseId + "' has an invalid drop pool element: " + node);
        }
        if (skinId == null || skinId.isBlank()) {
            throw new CatalogImportException("Case '" + caseId + "' has a drop pool entry with no skinId");
        }
        return new PoolEntry(skinId, weight);
    }

    // ---- validation --------------------------------------------------------

    private void validate(List<SkinCatalogEntry> skins, List<CaseCatalogEntry> cases,
                          Map<String, List<PoolEntry>> pools) {
        List<String> errors = new ArrayList<>();

        Set<String> skinIds = new HashSet<>();
        Set<String> duplicateSkinIds = new LinkedHashSet<>();
        for (SkinCatalogEntry s : skins) {
            if (s.getSkinId() == null || s.getSkinId().isBlank()) {
                errors.add("A skin entry is missing skinId");
                continue;
            }
            if (!skinIds.add(s.getSkinId())) {
                duplicateSkinIds.add(s.getSkinId());
            }
        }
        if (!duplicateSkinIds.isEmpty()) {
            errors.add("Duplicate skin IDs in skins.json: " + duplicateSkinIds);
        }

        Set<String> caseIds = new HashSet<>();
        Set<String> duplicateCaseIds = new LinkedHashSet<>();
        for (CaseCatalogEntry c : cases) {
            if (c.getCaseId() == null || c.getCaseId().isBlank()) {
                errors.add("A case entry is missing caseId");
                continue;
            }
            if (!caseIds.add(c.getCaseId())) {
                duplicateCaseIds.add(c.getCaseId());
            }
        }
        if (!duplicateCaseIds.isEmpty()) {
            errors.add("Duplicate case IDs in cases.json: " + duplicateCaseIds);
        }

        for (Map.Entry<String, List<PoolEntry>> e : pools.entrySet()) {
            for (PoolEntry pe : e.getValue()) {
                if (!skinIds.contains(pe.skinId())) {
                    errors.add("Case '" + e.getKey() + "' references unknown skinId '" + pe.skinId() + "'");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new CatalogImportException("Catalog validation failed:\n - " + String.join("\n - ", errors));
        }
    }

    // ---- import ------------------------------------------------------------

    private CatalogImportResult doImport(List<SkinCatalogEntry> skins, List<CaseCatalogEntry> cases,
                                         Map<String, List<PoolEntry>> pools) {
        // Upsert skins and cases first so the case_entries FKs are satisfied.
        List<Skin> skinEntities = skins.stream().map(this::toSkinEntity).toList();
        skinRepository.saveAll(skinEntities);

        List<CaseDefinition> caseEntities = cases.stream().map(this::toCaseEntity).toList();
        caseDefinitionRepository.saveAll(caseEntities);

        skinRepository.flush(); // force skins + cases to the DB before inserting entries

        // Replace each case's drop pool: clear old entries, then insert the current pool.
        int entryCount = 0;
        List<CaseEntry> newEntries = new ArrayList<>();
        for (CaseCatalogEntry c : cases) {
            caseEntryRepository.deleteEntriesByCaseId(c.getCaseId());
            for (PoolEntry pe : pools.getOrDefault(c.getCaseId(), List.of())) {
                CaseEntry entry = new CaseEntry();
                entry.setCaseId(c.getCaseId());
                entry.setSkinId(pe.skinId());
                entry.setWeight(pe.weight());
                newEntries.add(entry);
                entryCount++;
            }
        }
        caseEntryRepository.saveAll(newEntries);
        caseEntryRepository.flush();

        int neutralized = neutralizeSampleData();

        return new CatalogImportResult(skinEntities.size(), caseEntities.size(), entryCount, neutralized);
    }

    private Skin toSkinEntity(SkinCatalogEntry e) {
        Skin s = new Skin();
        s.setId(e.getSkinId());
        s.setDisplayName(e.getDisplayName());
        s.setWeapon(e.getWeapon());
        s.setRarity(e.getRarity());
        s.setVpValue(e.getVpValue());
        s.setImageRef(e.getResourceKey());
        s.setActive(e.isEnabled());
        return s;
    }

    private CaseDefinition toCaseEntity(CaseCatalogEntry e) {
        CaseDefinition c = new CaseDefinition();
        c.setId(e.getCaseId());
        c.setDisplayName(e.getDisplayName());
        c.setPriceVp(e.getPrice());
        c.setImageRef(e.getResourceKey());
        c.setActive(e.isEnabled());
        return c;
    }

    /**
     * Neutralizes the V3 sample data without breaking foreign keys: deletes the
     * sample case's entries (nothing references them) and deactivates the sample
     * skins and case so they vanish from the active-only catalog endpoints.
     */
    private int neutralizeSampleData() {
        int count = 0;

        List<CaseDefinition> sampleCases = caseDefinitionRepository.findAllById(SAMPLE_CASE_IDS);
        for (CaseDefinition c : sampleCases) {
            caseEntryRepository.deleteEntriesByCaseId(c.getId());
            c.setActive(false);
            count++;
        }
        caseDefinitionRepository.saveAll(sampleCases);

        List<Skin> sampleSkins = skinRepository.findAllById(SAMPLE_SKIN_IDS);
        for (Skin s : sampleSkins) {
            s.setActive(false);
        }
        skinRepository.saveAll(sampleSkins);
        count += sampleSkins.size();

        return count;
    }

    /** A normalized drop-pool entry (skinId + weight, default 1). */
    private record PoolEntry(String skinId, int weight) {
    }
}
