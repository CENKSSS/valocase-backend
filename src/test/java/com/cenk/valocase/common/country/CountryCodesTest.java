package com.cenk.valocase.common.country;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class CountryCodesTest {

    @Test
    void everyCountryTheClientOffersIsAccepted() {
        // The five the picker leads with, plus the rest of the launch markets.
        for (String code : new String[]{"TR", "IN", "PK", "DZ", "US", "GB", "DE", "FR", "JP", "KR"}) {
            assertTrue(CountryCodes.isValid(code), code);
            assertEquals(code, CountryCodes.canonical(code), code);
        }
    }

    @Test
    void lowercaseAndPaddedInputAreAcceptedAndComeBackUppercase() {
        assertEquals("TR", CountryCodes.canonical("tr"));
        assertEquals("TR", CountryCodes.canonical("Tr"));
        assertEquals("IN", CountryCodes.canonical(" in "));
        assertEquals("DZ", CountryCodes.canonical("\tdz\n"));
    }

    @Test
    void aCountryNameIsNeverACountryCode() {
        // The whole reason only the code is stored: these are what a client that
        // sends the label instead of the value would send, in several languages.
        for (String name : new String[]{"Türkiye", "Turkey", "India", "Pakistan", "Algeria",
                "United States", "Deutschland", "Türkiye - TR"}) {
            assertFalse(CountryCodes.isValid(name), name);
            assertNull(CountryCodes.canonical(name), name);
        }
    }

    @Test
    void alpha3AndOtherWrongShapesAreRejected() {
        for (String bad : new String[]{"TUR", "IND", "PAK", "DZA", "USA", "T", "TRX", "123", "T1", "1T"}) {
            assertFalse(CountryCodes.isValid(bad), bad);
        }
    }

    @Test
    void twoUppercaseLettersAreNotEnough() {
        // The point of an allowlist over a regex. Every one of these matches
        // ^[A-Z]{2}$ and none of them is a country.
        // AA is absent from this list on purpose: it is the one user-assigned code
        // this system does accept, as the "did not choose" marker.
        for (String bad : new String[]{"ZZ", "XX", "XA", "QQ", "XK", "UK", "EU", "OO"}) {
            assertTrue(bad.matches("^[A-Z]{2}$"), bad);
            assertFalse(CountryCodes.isValid(bad), bad + " is not an assigned ISO-3166-1 code");
        }
    }

    @Test
    void nullAndWhitespaceAreBlankRatherThanInvalid() {
        // Registration has to tell these apart: blank is tolerated during the
        // migration window, invalid never is.
        for (String blank : new String[]{null, "", " ", "\t", "\n", "   "}) {
            assertTrue(CountryCodes.isBlank(blank), String.valueOf(blank));
            assertFalse(CountryCodes.isValid(blank), String.valueOf(blank));
            assertNull(CountryCodes.normalize(blank), String.valueOf(blank));
        }
    }

    @Test
    void normalisationDoesNotDependOnTheServerLocale() {
        // Under a Turkish default locale, "in".toUpperCase() is "İN" — a dotted
        // capital I that no allowlist contains. If normalisation ever stopped
        // pinning Locale.ROOT, players from India would be unable to register on
        // a server whose locale happened to be tr-TR.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("IN", CountryCodes.canonical("in"));
            assertEquals("TR", CountryCodes.canonical("tr"));
            assertTrue(CountryCodes.isValid("in"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void theAllowlistMatchesTheJdkIsoTableExactly() {
        // The list is written out rather than read from the JDK so a JDK upgrade
        // cannot silently move it away from the SQL CHECK constraint. This is the
        // check that the frozen copy is still the right one: if a JDK upgrade
        // ever revises the standard's table, this fails and the Java literal and
        // the constraint get updated together, deliberately.
        // Compared against the official subset, not all(): all() also carries the
        // UNSPECIFIED marker, which is deliberately not an ISO country and would
        // make this check fail for the wrong reason.
        Set<String> jdk = new TreeSet<>(Arrays.asList(Locale.getISOCountries()));
        assertEquals(jdk, new TreeSet<>(CountryCodes.officialAlpha2()));
        assertEquals(249, CountryCodes.officialAlpha2().size());
    }

    @Test
    void theUnspecifiedMarkerIsAcceptedButIsNotAnIsoCountry() {
        // A player who reached the country screen and skipped it stores AA. It has
        // to pass validation like any other code, while staying out of the official
        // list so country reports can tell a real country from a skip.
        assertEquals("AA", CountryCodes.UNSPECIFIED);
        assertTrue(CountryCodes.isValid("AA"));
        assertEquals("AA", CountryCodes.canonical("aa"));
        assertTrue(CountryCodes.all().contains("AA"));
        assertFalse(CountryCodes.officialAlpha2().contains("AA"));
        assertEquals(250, CountryCodes.all().size());
    }

    @Test
    void skippingIsNotTheSameAsNeverBeingAsked() {
        // AA is a stored value; blank is an absence. Registration treats them
        // differently and nothing should blur the two.
        assertFalse(CountryCodes.isBlank("AA"));
        assertTrue(CountryCodes.isBlank(null));
        assertNull(CountryCodes.canonical(null));
    }

    @Test
    void theAllowlistCannotBeModifiedByACaller() {
        assertThrows(UnsupportedOperationException.class, () -> CountryCodes.all().add("ZZ"));
    }

    @Test
    void everyAcceptedCodeIsTwoUppercaseAsciiLetters() {
        // What the VARCHAR(2) columns rely on.
        for (String code : CountryCodes.all()) {
            assertEquals(CountryCodes.CODE_LENGTH, code.length(), code);
            assertTrue(code.matches("^[A-Z]{2}$"), code);
        }
    }
}
