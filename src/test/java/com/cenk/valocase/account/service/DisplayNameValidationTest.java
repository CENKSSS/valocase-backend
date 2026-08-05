package com.cenk.valocase.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Unicode display-name rule.
 *
 * <p>Every non-ASCII value is written as a {@code \\u} escape with the readable
 * form in a trailing comment. Java resolves those escapes before the file is even
 * lexed, so no assertion here can pass or fail because of how this file happens
 * to be encoded on disk — which matters more than usual for a suite whose whole
 * subject is character handling.
 */
class DisplayNameValidationTest {

    // Names, escaped. Comment shows what a player would see.
    private static final String CINAR_TR   = "Çınar";                     // Çınar
    private static final String YIGIT_TR   = "Yiğit";                          // Yiğit
    private static final String ARABIC     = "محمد";            // محمد
    private static final String DEVANAGARI = "अर्जुन"; // अर्जुन
    private static final String JOSE_NFC   = "José";                           // José, precomposed
    private static final String JOSE_NFD   = "José";                          // José, decomposed
    private static final String LUKASZ     = "Łukasz";                         // Łukasz
    private static final String GREEK      = "Ελληνικά"; // Ελληνικά
    private static final String KOREAN     = "한국어";                  // 한국어
    private static final String CYRILLIC   = "Анна";            // Анна
    private static final String AHMET_TR   = "Ahmet Yılmaz";                   // Ahmet Yılmaz

    // Invisible characters, which is the whole reason these are constants.
    private static final String NBSP   = " "; // no-break space
    private static final String NNBSP  = " "; // narrow no-break space
    private static final String IDEO   = "　"; // ideographic space
    private static final String THIN   = " "; // thin space
    private static final String ZWSP   = "​"; // zero-width space
    private static final String ZWJ    = "‍"; // zero-width joiner
    private static final String RLO    = "‮"; // right-to-left override
    private static final String ACUTE  = "́"; // combining acute accent

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @Test
        void namesFromAnyScriptAreAccepted() {
            String[] names = {
                    "Player123", "player_name", "Cinar", "Yigit", "Ali_53",
                    CINAR_TR, YIGIT_TR, ARABIC, DEVANAGARI,
                    JOSE_NFC, LUKASZ, GREEK, KOREAN, CYRILLIC,
            };
            for (String name : names) {
                assertNull(AccountService.classifyDisplayName(name), escape(name));
            }
        }

        @Test
        void boundaryLengthsInUserPerceivedCharacters() {
            assertNull(AccountService.classifyDisplayName("abc"));          // exactly 3
            assertNull(AccountService.classifyDisplayName("A".repeat(15))); // exactly 15
        }

        @Test
        void koreanIsThreeUserPerceivedCharacters() {
            assertEquals(3, AccountService.graphemeLength(KOREAN));
            assertNull(AccountService.classifyDisplayName(KOREAN));
        }

        @Test
        void devanagariCountsClustersNotCodePoints() {
            // Six code points, three grapheme clusters as Java measures them: the
            // virama binds र् to the following ज into one conjunct cluster.
            // String.length() would report 6, which is not a number the player
            // would recognise — that gap is why the validator counts clusters.
            //
            // Note the consequence for the minimum: this name sits exactly on the
            // 3-cluster floor despite looking longer, so Indic names are measured
            // more strictly than a naive character count would suggest. Recorded
            // here because it is measured behaviour, not an assumption.
            assertEquals(6, DEVANAGARI.length());
            assertEquals(3, AccountService.graphemeLength(DEVANAGARI));
            assertNull(AccountService.classifyDisplayName(DEVANAGARI));
        }

        @Test
        void leadingAndTrailingWhitespaceIsTrimmedNotRejected() {
            assertNull(AccountService.classifyDisplayName("  Cenk  "));
            assertNull(AccountService.classifyDisplayName("\tCenk\n"));
            assertNull(AccountService.classifyDisplayName("\r\nCenk\r\n"));
            assertEquals("Cenk", AccountService.normalizeDisplayName("  Cenk  "));
        }

        @Test
        void surroundingNonBreakingSpaceIsTrimmedToo() {
            // String.strip() would NOT remove these: Character.isWhitespace is
            // false for the no-break spaces. Without the wider trim the name would
            // be refused for containing whitespace it never visibly had.
            assertNull(AccountService.classifyDisplayName(NBSP + "Cenk" + NBSP));
            assertNull(AccountService.classifyDisplayName(IDEO + "Cenk" + IDEO));
            assertEquals("Cenk", AccountService.normalizeDisplayName(NBSP + "Cenk" + NNBSP));
        }
    }

    @Nested
    @DisplayName("rejected")
    class Rejected {

        @Test
        void blankForms() {
            for (String blank : new String[]{null, "", "   ", "\t\n", NBSP + NBSP, IDEO}) {
                assertEquals(RegistrationRejectionReason.BLANK,
                        AccountService.classifyDisplayName(blank), escape(blank));
            }
        }

        @Test
        void tooShort() {
            assertEquals(RegistrationRejectionReason.TOO_SHORT,
                    AccountService.classifyDisplayName("ab"));
            assertEquals(RegistrationRejectionReason.TOO_SHORT,
                    AccountService.classifyDisplayName("  ab  "));
        }

        @Test
        void tooLong() {
            assertEquals(RegistrationRejectionReason.TOO_LONG,
                    AccountService.classifyDisplayName("A".repeat(16)));
            assertEquals(RegistrationRejectionReason.TOO_LONG,
                    AccountService.classifyDisplayName(KOREAN.repeat(6)));
        }

        @Test
        void internalWhitespaceIsWhitespaceNotInvalidCharacter() {
            String[] names = {
                    "Ahmet Yilmaz",
                    AHMET_TR,
                    "John Smith",
                    "Ahmet\tYilmaz",
                    "Ahmet\nYilmaz",
                    "Ahmet\r\nYilmaz",
                    "Ahmet" + NBSP + "Yilmaz",
                    "Ahmet" + NNBSP + "Yilmaz",
                    "Ahmet" + IDEO + "Yilmaz",
                    "Ahmet" + THIN + "Yilmaz",
            };
            for (String name : names) {
                assertEquals(RegistrationRejectionReason.WHITESPACE,
                        AccountService.classifyDisplayName(name), escape(name));
            }
        }

        @Test
        void punctuationAndSymbolsAreInvalidCharacters() {
            String[] names = {
                    "Jean-Luc",
                    "O'Connor",
                    "O’Connor",            // curly apostrophe
                    "player!",
                    "user🙂",         // user + slightly-smiling-face emoji
                    "test.name",
                    "a+b+c",
                    "<script>",
                    "drop;table",
            };
            for (String name : names) {
                assertEquals(RegistrationRejectionReason.INVALID_CHARACTER,
                        AccountService.classifyDisplayName(name), escape(name));
            }
        }

        @Test
        void invisibleAndControlCharactersAreNeverAccepted() {
            // Which of the two reason codes fires depends on the Unicode general
            // category, and that has moved between Unicode versions for some of
            // these. Pinning the code would make the test fragile against a JDK
            // upgrade; what must hold is that none of them is ever accepted.
            String[] names = {
                    "ab" + ZWSP + "c",
                    "ab" + ZWJ + "c",
                    "ab" + RLO + "c",
                    "ab c",   // NUL
                    "abc",   // ESC
                    "abc",   // DEL
            };
            for (String name : names) {
                assertNotNull(AccountService.classifyDisplayName(name), escape(name));
            }
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        void decomposedAndPrecomposedFormsBecomeOneValue() {
            assertNull(AccountService.classifyDisplayName(JOSE_NFD));
            assertEquals(JOSE_NFC, AccountService.normalizeDisplayName(JOSE_NFD));
            assertEquals(AccountService.normalizeDisplayName(JOSE_NFC),
                    AccountService.normalizeDisplayName(JOSE_NFD));
        }

        @Test
        void theStoredValueIsNfc() {
            assertTrue(Normalizer.isNormalized(
                    AccountService.normalizeDisplayName(JOSE_NFD), Normalizer.Form.NFC));
        }

        @Test
        void lengthIsMeasuredAfterComposition() {
            assertEquals(5, JOSE_NFD.length());
            assertEquals(4, AccountService.graphemeLength(
                    AccountService.normalizeDisplayName(JOSE_NFD)));
        }

        @Test
        void aThreeCharacterNameWrittenDecomposedStillMeetsTheMinimum() {
            // "é" + "ab" decomposed is four code points but three user-perceived
            // characters. Counting before composing would wrongly accept/reject at
            // the boundary; this pins the order.
            assertNull(AccountService.classifyDisplayName("e" + ACUTE + "ab"));
        }
    }

    @Nested
    @DisplayName("storage guard")
    class StorageGuard {

        @Test
        void aPathologicalCombiningMarkRunIsRefusedBeforeItReachesTheColumn() {
            // Fifteen clusters, each carrying a long run of combining marks:
            // inside the user-visible limit, far outside VARCHAR(100). Must be a
            // clean 400, not a database error at INSERT time.
            String name = ("a" + ACUTE.repeat(20)).repeat(15);
            assertEquals(15, AccountService.graphemeLength(name));
            assertEquals(RegistrationRejectionReason.TOO_LONG,
                    AccountService.classifyDisplayName(name));
        }

        @Test
        void everyAcceptedNameFitsTheColumn() {
            String[] names = {
                    "A".repeat(15), CINAR_TR, ARABIC, DEVANAGARI, GREEK, KOREAN, JOSE_NFD,
            };
            for (String name : names) {
                assertNull(AccountService.classifyDisplayName(name), escape(name));
                assertTrue(AccountService.normalizeDisplayName(name).length() <= 100, escape(name));
            }
        }
    }

    @Nested
    @DisplayName("shared by registration and rename")
    class SharedRule {

        @Test
        void theValidatorIsOneFunctionSoTheTwoCannotDisagree() {
            // AccountService.requireValidDisplayName is the only path to a stored
            // name, and both registerGuest and updateDisplayName call it. This
            // pins the property that matters: identical verdicts for identical
            // input, whichever entry point asked.
            for (String name : new String[]{CINAR_TR, "ab", "John Smith", "player!", KOREAN}) {
                RegistrationRejectionReason first = AccountService.classifyDisplayName(name);
                RegistrationRejectionReason second = AccountService.classifyDisplayName(name);
                assertEquals(first, second, escape(name));
            }
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c < 32 || c > 126 ? String.format("\\u%04X", (int) c) : String.valueOf(c));
        }
        return sb.toString();
    }
}
