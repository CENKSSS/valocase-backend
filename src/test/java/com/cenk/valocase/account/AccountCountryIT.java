package com.cenk.valocase.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.dto.AccountProfileResponse;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.country.CountryCodes;
import com.cenk.valocase.common.exception.ApiException;

/**
 * Country selection against a real Flyway-migrated PostgreSQL.
 *
 * <p>What only a database can prove: that V80 applies, that a code survives the
 * round trip through a {@code VARCHAR(2)}, that the CHECK constraint refuses
 * what the application refuses, that rows written before the column existed are
 * still readable, and that the reporting views compile against the real schema.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any PostgreSQL and run it.
 */
@SpringBootTest
class AccountCountryIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired RegistrationProperties registrationProperties;

    private String uniqueName() {
        return "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String storedCountry(String accountId) {
        return jdbc.queryForObject(
                "SELECT country_code FROM accounts WHERE id = ?::uuid", String.class, accountId);
    }

    // --- the column and the migration -----------------------------------------

    @Test
    void theMigrationAddedANullableTwoCharacterColumn() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'accounts' AND column_name = 'country_code'");

        assertEquals("character varying", column.get("data_type"));
        assertEquals(2, ((Number) column.get("character_maximum_length")).intValue());
        assertEquals("YES", column.get("is_nullable"), "old accounts have no country");
    }

    @Test
    void noLocalizedCountryNameColumnWasAdded() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'accounts'",
                String.class);

        for (String forbidden : new String[]{"country", "country_name", "country_label", "region"}) {
            assertTrue(columns.stream().noneMatch(c -> c.equalsIgnoreCase(forbidden)),
                    "forbidden column present: " + forbidden);
        }
        assertTrue(columns.stream().anyMatch(c -> c.equals("country_code")));
    }

    // --- round trip -----------------------------------------------------------

    @Test
    void aSelectedCountryRoundTripsThroughPostgres() {
        for (String code : new String[]{"TR", "IN", "PK", "DZ", "US", "GB", "DE", "FR", "JP", "KR"}) {
            GuestRegisterResponse response = accountService.registerGuest(uniqueName(), code);

            assertEquals(code, response.countryCode(), code);
            assertEquals(code, storedCountry(response.accountId()), "round trip changed: " + code);
        }
    }

    @Test
    void aLowercaseCodeIsStoredAndReturnedUppercase() {
        GuestRegisterResponse response = accountService.registerGuest(uniqueName(), "tr");

        assertEquals("TR", response.countryCode(), "response");
        assertEquals("TR", storedCountry(response.accountId()), "database");
    }

    @Test
    void unicodeDisplayNamesAndCountriesCoexist() {
        // Both halves of the registration body at once, through the real column
        // encodings: a Turkish name must not be damaged by the country, or vice
        // versa.
        GuestRegisterResponse response = accountService.registerGuest("Yiğit", "tr");

        assertEquals("Yiğit", response.displayName());
        assertEquals("TR", response.countryCode());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT display_name, country_code FROM accounts WHERE id = ?::uuid",
                response.accountId());
        assertEquals("Yiğit", row.get("display_name"));
        assertEquals("TR", row.get("country_code"));
    }

    // --- the migration window -------------------------------------------------

    @Test
    void aRequestWithoutACountryStillRegistersAndStoresNull() {
        assertTrue(registrationProperties.isRequireCountryCode() == false,
                "the shipped default must keep the old client working");

        GuestRegisterResponse response = accountService.registerGuest(uniqueName(), null);

        assertNull(response.countryCode());
        assertNull(storedCountry(response.accountId()), "nothing may be invented for it");
    }

    @Test
    void accountsWrittenBeforeTheColumnExistedAreStillReadable() {
        // Every row in production today. They must load through JPA, keep their
        // balance and name, and simply have no country.
        GuestRegisterResponse response = accountService.registerGuest(uniqueName(), null);
        UUID id = UUID.fromString(response.accountId());

        Account account = accountRepository.findById(id).orElseThrow();
        assertNull(account.getCountryCode());
        assertEquals(response.displayName(), account.getDisplayName());
    }

    @Test
    void anInvalidCountryIsRefusedAndLeavesNoAccountBehind() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);

        for (String bad : new String[]{"TUR", "Turkey", "Türkiye", "123", "T1", "ZZ", "XX"}) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> accountService.registerGuest(uniqueName(), bad), bad);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), bad);
        }

        assertEquals(before,
                (int) jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
    }

    // --- the database is the last line ----------------------------------------

    @Test
    void theDatabaseRefusesACountryTheApplicationWouldHaveRefused() {
        // The application check protects the API. This one protects the column
        // from an ad-hoc UPDATE or a future code path that forgets the validator.
        GuestRegisterResponse response = accountService.registerGuest(uniqueName(), "TR");

        for (String bad : new String[]{"ZZ", "XX", "tr", "T1", "1", "  "}) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "UPDATE accounts SET country_code = ? WHERE id = ?::uuid",
                    bad, response.accountId()), "database accepted: " + bad);
        }

        assertEquals("TR", storedCountry(response.accountId()), "a refused write changed nothing");
    }

    @Test
    void theCheckConstraintHoldsExactlyTheApplicationAllowlist() {
        // The two copies of the ISO list — the Java literal and the SQL IN clause
        // — are only safe because this test fails the moment they disagree.
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_accounts_country_code'", String.class);
        assertNotNull(definition, "the CHECK constraint is missing");

        Set<String> inConstraint = new TreeSet<>();
        java.util.regex.Matcher codes =
                java.util.regex.Pattern.compile("'([A-Z]{2})'").matcher(definition);
        while (codes.find()) {
            inConstraint.add(codes.group(1));
        }

        assertEquals(new TreeSet<>(CountryCodes.all()), inConstraint,
                "the CHECK constraint and CountryCodes have drifted apart");
    }

    @Test
    void theTelemetryTableCarriesTheSameConstraint() {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_onboarding_events_country_code'", String.class);
        assertNotNull(definition);
        assertTrue(definition.contains("'TR'"));
        assertTrue(definition.contains("IS NULL"), "telemetry rows without a country are legal");
    }

    // --- changing the country later -------------------------------------------

    @Test
    void aPlayerCanChangeTheirCountryAndTheChangeIsPersisted() {
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), "TR");
        Account account = accountRepository.findById(UUID.fromString(registered.accountId()))
                .orElseThrow();

        AccountProfileResponse response = accountService.updateCountryCode(account, "in");

        assertEquals("IN", response.countryCode());
        assertEquals("IN", storedCountry(registered.accountId()));
    }

    @Test
    void anAccountWithNoCountryCanSetOneFromSettings() {
        // The whole reason this endpoint exists: every account that predates the
        // country screen fills its NULL in this way, and only this way.
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), null);
        assertNull(storedCountry(registered.accountId()));
        Account account = accountRepository.findById(UUID.fromString(registered.accountId()))
                .orElseThrow();

        accountService.updateCountryCode(account, "pk");

        assertEquals("PK", storedCountry(registered.accountId()));
    }

    @Test
    void aRefusedChangeLeavesTheStoredCountryUntouched() {
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), "DZ");
        Account account = accountRepository.findById(UUID.fromString(registered.accountId()))
                .orElseThrow();

        for (String bad : new String[]{"Türkiye", "TUR", "ZZ", "", null}) {
            assertThrows(ApiException.class,
                    () -> accountService.updateCountryCode(account, bad), String.valueOf(bad));
        }

        assertEquals("DZ", storedCountry(registered.accountId()));
    }

    @Test
    void aCountryChangeLeavesTheNicknameAlone() {
        String name = uniqueName();
        GuestRegisterResponse registered = accountService.registerGuest(name, "TR");
        Account account = accountRepository.findById(UUID.fromString(registered.accountId()))
                .orElseThrow();

        AccountProfileResponse response = accountService.updateCountryCode(account, "US");

        assertEquals(name, response.displayName());
        assertEquals(name, jdbc.queryForObject(
                "SELECT display_name FROM accounts WHERE id = ?::uuid",
                String.class, registered.accountId()));
    }

    // --- reporting ------------------------------------------------------------

    @Test
    void theCountryReportingViewsCompileAndCountWhatWasSelected() {
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), "DZ");
        assertNotNull(registered.countryCode());

        for (String view : new String[]{
                "admin_accounts_by_country",
                "admin_daily_new_accounts_by_country",
                "admin_sessions_by_country",
                "admin_onboarding_funnel_by_country",
                "admin_nickname_rejections_by_country"}) {
            // Executing it is the only real proof it compiles against the schema.
            jdbc.queryForList("SELECT * FROM " + view + " LIMIT 5");
        }

        Integer algerians = jdbc.queryForObject(
                "SELECT account_count FROM admin_accounts_by_country WHERE country_code = 'DZ'",
                Integer.class);
        assertTrue(algerians >= 1);
    }

    @Test
    void accountsWithNoCountryAppearUnderUnknownRatherThanVanishing() {
        accountService.registerGuest(uniqueName(), null);

        Integer unknown = jdbc.queryForObject(
                "SELECT account_count FROM admin_accounts_by_country WHERE country_code = 'UNKNOWN'",
                Integer.class);
        assertTrue(unknown >= 1, "the migration-window bucket must be visible, not silently dropped");
    }

    @Test
    void theAdminPlayerDetailViewExposesTheCountry() {
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), "KR");

        String country = jdbc.queryForObject(
                "SELECT country_code FROM admin_user_analytics WHERE user_id = ?::uuid",
                String.class, registered.accountId());

        assertEquals("KR", country);
    }

    @Test
    void everyOfficialCodeIsActuallyInsertable() {
        // Set membership proved against the running database rather than against
        // the constraint's text: a code the application accepts but the column
        // refuses would be a 500 in production, at registration.
        GuestRegisterResponse registered = accountService.registerGuest(uniqueName(), "TR");
        for (String code : new TreeSet<>(CountryCodes.all())) {
            jdbc.update("UPDATE accounts SET country_code = ? WHERE id = ?::uuid",
                    code, registered.accountId());
        }
        assertEquals("ZW", storedCountry(registered.accountId()));
    }

    @Test
    void theAllowlistCoversEveryCountryTheClientPickerCanShow() {
        assertTrue(CountryCodes.all().containsAll(
                Arrays.asList("TR", "IN", "PK", "DZ", "US", "GB", "DE", "FR", "JP", "KR")));
    }
}
