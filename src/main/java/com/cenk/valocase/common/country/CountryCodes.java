package com.cenk.valocase.common.country;

import java.util.Locale;
import java.util.Set;

/**
 * The one country-code validator. Registration, the later country change, and
 * onboarding telemetry all go through here, so a code that is acceptable in one
 * of them cannot be unacceptable in another.
 *
 * <p><strong>Only the ISO-3166-1 alpha-2 code is ever handled.</strong> The
 * localized country name a player sees ("Türkiye", "Algérie") belongs to the
 * client's language files and never travels to the backend or into a column.
 * Storing a name would make the same country several different values depending
 * on the device language, which is exactly what a code prevents.
 *
 * <p>The allowlist below is written out rather than taken from
 * {@link Locale#getISOCountries()}. It matches that set exactly today, but a JDK
 * upgrade can revise the JDK's copy of the standard, and the same list is
 * duplicated in the {@code ck_accounts_country_code} CHECK constraint. A literal
 * cannot drift under a JDK upgrade; {@code CountryCodesTest} pins the two
 * together and a database test pins the constraint to this set.
 *
 * <p>Length is <em>not</em> the rule. "T1", "ZZ" and "XX" are all two uppercase
 * characters and all rejected, because membership of the official set is the
 * rule and a shape check is only a cheap prefilter.
 */
public final class CountryCodes {

    /** Every accepted code is exactly this long, in ASCII characters. */
    public static final int CODE_LENGTH = 2;

    /**
     * The 249 officially assigned ISO-3166-1 alpha-2 codes.
     *
     * <p>Deliberately absent: the user-assigned range ({@code XA}-{@code XZ},
     * {@code ZZ}, {@code AA}, {@code QM}-{@code QZ}), the unofficial {@code XK}
     * for Kosovo, the ccTLD-but-not-ISO {@code UK}, the exceptionally reserved
     * {@code EU}, and withdrawn codes such as {@code AN} and {@code CS}.
     */
    private static final Set<String> ISO_ALPHA_2 = Set.of(
            "AD", "AE", "AF", "AG", "AI", "AL", "AM", "AO", "AQ", "AR", "AS", "AT",
            "AU", "AW", "AX", "AZ", "BA", "BB", "BD", "BE", "BF", "BG", "BH", "BI",
            "BJ", "BL", "BM", "BN", "BO", "BQ", "BR", "BS", "BT", "BV", "BW", "BY",
            "BZ", "CA", "CC", "CD", "CF", "CG", "CH", "CI", "CK", "CL", "CM", "CN",
            "CO", "CR", "CU", "CV", "CW", "CX", "CY", "CZ", "DE", "DJ", "DK", "DM",
            "DO", "DZ", "EC", "EE", "EG", "EH", "ER", "ES", "ET", "FI", "FJ", "FK",
            "FM", "FO", "FR", "GA", "GB", "GD", "GE", "GF", "GG", "GH", "GI", "GL",
            "GM", "GN", "GP", "GQ", "GR", "GS", "GT", "GU", "GW", "GY", "HK", "HM",
            "HN", "HR", "HT", "HU", "ID", "IE", "IL", "IM", "IN", "IO", "IQ", "IR",
            "IS", "IT", "JE", "JM", "JO", "JP", "KE", "KG", "KH", "KI", "KM", "KN",
            "KP", "KR", "KW", "KY", "KZ", "LA", "LB", "LC", "LI", "LK", "LR", "LS",
            "LT", "LU", "LV", "LY", "MA", "MC", "MD", "ME", "MF", "MG", "MH", "MK",
            "ML", "MM", "MN", "MO", "MP", "MQ", "MR", "MS", "MT", "MU", "MV", "MW",
            "MX", "MY", "MZ", "NA", "NC", "NE", "NF", "NG", "NI", "NL", "NO", "NP",
            "NR", "NU", "NZ", "OM", "PA", "PE", "PF", "PG", "PH", "PK", "PL", "PM",
            "PN", "PR", "PS", "PT", "PW", "PY", "QA", "RE", "RO", "RS", "RU", "RW",
            "SA", "SB", "SC", "SD", "SE", "SG", "SH", "SI", "SJ", "SK", "SL", "SM",
            "SN", "SO", "SR", "SS", "ST", "SV", "SX", "SY", "SZ", "TC", "TD", "TF",
            "TG", "TH", "TJ", "TK", "TL", "TM", "TN", "TO", "TR", "TT", "TV", "TW",
            "TZ", "UA", "UG", "UM", "US", "UY", "UZ", "VA", "VC", "VE", "VG", "VI",
            "VN", "VU", "WF", "WS", "YE", "YT", "ZA", "ZM", "ZW");

    private CountryCodes() {
    }

    /** Every accepted code, uppercase. Immutable. */
    public static Set<String> all() {
        return ISO_ALPHA_2;
    }

    /**
     * The canonical form of a submitted country code: trimmed, then uppercased
     * under {@link Locale#ROOT}.
     *
     * <p>{@code Locale.ROOT} is not decoration. Under a Turkish default locale
     * {@code "tr".toUpperCase()} produces {@code "TR"} but {@code "in"} produces
     * {@code "İN"} — a dotted capital I that is not in any allowlist. The server
     * locale must never decide whether a player from India can register.
     *
     * @return the canonical form, or {@code null} when the input is null or has
     *         nothing left after trimming
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * The stored form of a submitted country code, or {@code null} when it is
     * absent <em>or</em> not an official code.
     *
     * <p>Callers that must tell "absent" from "invalid" apart — registration
     * does, because one may be permitted and the other never is — should use
     * {@link #isBlank} and {@link #isValid} rather than reading meaning into a
     * null returned by this method.
     */
    public static String canonical(String raw) {
        String normalized = normalize(raw);
        return normalized != null && ISO_ALPHA_2.contains(normalized) ? normalized : null;
    }

    /** True when nothing was supplied: null, empty, or whitespace only. */
    public static boolean isBlank(String raw) {
        return normalize(raw) == null;
    }

    /** True only for an official code, in any case. False for blank and for null. */
    public static boolean isValid(String raw) {
        return canonical(raw) != null;
    }
}
