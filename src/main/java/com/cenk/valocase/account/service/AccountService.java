package com.cenk.valocase.account.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.dto.AccountAvatarResponse;
import com.cenk.valocase.account.dto.AccountProfileResponse;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.analytics.service.PlayerActivityService;
import com.cenk.valocase.common.country.CountryCodes;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Guest account creation and token-based resolution. No passwords or external
 * login in Phase 1 — the guestToken is the only credential.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    /** Starting VP balance granted to every new guest. */
    public static final long STARTING_VP = 17_500L;

    /** Minimum length of a display name, in user-perceived characters. */
    public static final int DISPLAY_NAME_MIN_LENGTH = 3;
    /** Maximum length of a display name, in user-perceived characters. */
    public static final int DISPLAY_NAME_MAX_LENGTH = 15;

    /**
     * Storage guard, in Java chars, applied after normalisation.
     *
     * <p>Grapheme clusters have no fixed char cost: a single cluster can carry an
     * unbounded run of combining marks, so 15 clusters could otherwise exceed the
     * {@code accounts.display_name VARCHAR(100)} column and fail at INSERT time
     * with a database error instead of a clean 400. This bound keeps every
     * accepted name comfortably inside the column.
     */
    static final int DISPLAY_NAME_MAX_CHARS = 60;

    /** Default avatar for new accounts and the fallback for null/blank avatars. */
    public static final String DEFAULT_AVATAR_ID = "avatar_1";
    /** Maximum length of an avatar id. */
    public static final int AVATAR_ID_MAX_LENGTH = 50;

    private static final java.util.regex.Pattern AVATAR_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Counter reason for a registration refused because no country was sent. */
    static final String COUNTRY_MISSING = "COUNTRY_MISSING";
    /** Counter reason for a registration refused because the country was not an ISO code. */
    static final String COUNTRY_INVALID = "COUNTRY_INVALID";

    private final AccountRepository accountRepository;
    private final WalletService walletService;
    private final PlayerActivityService playerActivityService;
    private final DiagnosticCounters diagnosticCounters;
    private final RegistrationProperties registrationProperties;

    /**
     * Creates a guest account under the nickname the player has already chosen.
     *
     * <p>The nickname is required, and that requirement is the account-creation
     * guard rather than a formality. This endpoint is unauthenticated and hands
     * out {@value #STARTING_VP} VP, so an empty POST used to be enough to mint an
     * account — which is exactly how a fleet of unused accounts appeared. Only a
     * client that has walked a player through the nickname screen can send one,
     * so a bare request now creates nothing.
     *
     * <p>The name is validated before any write, so a rejected request leaves no
     * account, no wallet and no starting balance behind.
     *
     * <p>The country is validated the same way but is optional for now: while
     * {@code valocase.registration.require-country-code} is off, a request
     * without one still creates the account and leaves {@code countryCode} null.
     * That is what keeps the Unity build already in the store — which knows
     * nothing about countries — able to register. An invalid country is refused
     * whichever way the switch is set: absent and wrong are different things.
     *
     * <p>Every outcome is logged with a reason code and counted. A rejection used
     * to leave no trace anywhere, which made a total registration outage
     * indistinguishable from nobody having installed the game. The nickname
     * itself is never logged — only its length and the rule that refused it. The
     * country code is loggable: it is one of 249 fixed values chosen from a list,
     * not text the player typed.
     */
    @Transactional
    public GuestRegisterResponse registerGuest(String rawDisplayName, String rawCountryCode) {
        return registerGuest(rawDisplayName, rawCountryCode, null);
    }

    /**
     * Registration with the caller's install id, as sent by clients that carry
     * the field. Behaviour is identical to the two-argument form in every
     * respect other than the column written: the id is analytics data and no
     * validation, rejection, ordering or response value depends on it.
     *
     * @param rawInstallationId the client's own per-install UUID, or null
     */
    @Transactional
    public GuestRegisterResponse registerGuest(String rawDisplayName, String rawCountryCode,
                                               String rawInstallationId) {
        diagnosticCounters.recordGuestRegistrationStarted();
        log.info("guest registration started");

        String displayName;
        try {
            displayName = requireValidDisplayName(rawDisplayName);
        } catch (ApiException rejected) {
            recordRejection(classifyDisplayName(rawDisplayName));
            throw rejected;
        }
        String countryCode = requireAcceptableCountryCode(rawCountryCode);
        UUID installationId = resolveInstallationId(rawInstallationId);
        Instant now = Instant.now();

        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setDisplayName(displayName);
        account.setAvatarId(DEFAULT_AVATAR_ID);
        account.setCountryCode(countryCode);
        account.setInstallationId(installationId);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(now);
        account.setLastSeenAt(now);

        // Duplicate display names stay legal and there is no unique constraint on
        // the column, so no integrity violation is anticipated here and none is
        // caught: an unexpected one should surface rather than be relabelled.
        account = accountRepository.save(account);
        Wallet wallet = walletService.createInitialWallet(account.getId(), STARTING_VP);

        playerActivityService.recordActivity(account.getId());

        diagnosticCounters.recordGuestRegistrationSuccess();
        // The install id is truncated in logs: eight hex characters are enough to
        // correlate one registration with its telemetry while reading a log, and
        // the full id stays in the database where it is actually joined.
        log.info("guest registration created: accountId={} country={} installation={}",
                account.getId(), account.getCountryCode(),
                shortInstallation(account.getInstallationId()));

        return new GuestRegisterResponse(
                account.getId().toString(),
                account.getGuestToken().toString(),
                account.getDisplayName(),
                account.getAvatarId(),
                account.getCountryCode(),
                account.getStatus().name(),
                wallet.getVpBalance(),
                wallet.getDiamondBalance()
        );
    }

    @Transactional
    public AccountProfileResponse updateDisplayName(Account account, String rawDisplayName) {
        account.setDisplayName(requireValidDisplayName(rawDisplayName));
        accountRepository.save(account);
        return profileOf(account);
    }

    /**
     * Changes the account's country from the Settings screen.
     *
     * <p>The account comes from the {@code X-Guest-Token} header, resolved by the
     * caller — there is no path by which a client names the account it is
     * editing, so one player cannot rewrite another's country.
     *
     * <p>Unlike registration this always requires a country: the player opened a
     * picker and chose an entry, so "no country" is not an outcome the screen can
     * produce, and treating a blank as "clear it" would let a bug silently undo a
     * selection. Validation runs before the assignment, so a refused code leaves
     * the previously stored one exactly as it was — including the null that an
     * account created before the country screen still carries, which this
     * endpoint is precisely how a returning player fills in.
     */
    @Transactional
    public AccountProfileResponse updateCountryCode(Account account, String rawCountryCode) {
        if (CountryCodes.isBlank(rawCountryCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "countryCode is required");
        }
        String countryCode = CountryCodes.canonical(rawCountryCode);
        if (countryCode == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_COUNTRY_MESSAGE);
        }

        account.setCountryCode(countryCode);
        accountRepository.save(account);
        log.info("account country updated: accountId={} country={}", account.getId(), countryCode);
        return profileOf(account);
    }

    /** The profile view of an account, as returned by every mutation of it. */
    private static AccountProfileResponse profileOf(Account account) {
        return new AccountProfileResponse(
                account.getId().toString(), account.getDisplayName(), account.getCountryCode());
    }

    /** The 400 message for a country code that is not on the ISO allowlist. */
    private static final String INVALID_COUNTRY_MESSAGE =
            "countryCode must be an ISO-3166-1 alpha-2 country code, e.g. TR";

    /**
     * Validates the country supplied at registration and returns the value to
     * store, which is null when none was sent and none is required yet.
     *
     * <p>Missing and invalid are kept apart on purpose. Missing is temporarily
     * tolerated because clients that predate the country screen cannot send one;
     * invalid never is, in either configuration, because a client that sends
     * "Türkiye" or "TUR" is broken rather than old and silently storing null for
     * it would hide that.
     *
     * @throws ApiException 400 when the country is unusable
     */
    private String requireAcceptableCountryCode(String rawCountryCode) {
        if (CountryCodes.isBlank(rawCountryCode)) {
            if (registrationProperties.isRequireCountryCode()) {
                recordCountryRejection(COUNTRY_MISSING);
                throw new ApiException(HttpStatus.BAD_REQUEST, "countryCode is required");
            }
            return null;
        }
        String countryCode = CountryCodes.canonical(rawCountryCode);
        if (countryCode == null) {
            recordCountryRejection(COUNTRY_INVALID);
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_COUNTRY_MESSAGE);
        }
        return countryCode;
    }

    /**
     * Parses the install id supplied at registration, or returns null.
     *
     * <p><strong>Never throws.</strong> This is the one validation in this class
     * that cannot refuse a registration, and the asymmetry is deliberate. A
     * nickname and a country are things the player chose and the game needs; the
     * install id is a measurement. Failing a registration because an analytics
     * field was malformed would trade a real player for a data point, so a value
     * that will not parse is dropped, logged at WARN, and the account is created
     * without it — exactly as it would be for a client too old to send one.
     *
     * <p>Blank and malformed are logged differently on purpose: blank is the
     * normal shape of an older client and says nothing, while an unparseable
     * value means a client is sending something we did not design for and is
     * worth seeing in the log. The rejected text itself is never logged — it is
     * unvalidated client input — only its length.
     */
    private UUID resolveInstallationId(String rawInstallationId) {
        if (rawInstallationId == null || rawInstallationId.isBlank()) {
            return null;
        }
        String trimmed = rawInstallationId.trim();
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException malformed) {
            log.warn("guest registration installationId dropped: malformed, length={}",
                    trimmed.length());
            return null;
        }
    }

    /**
     * The log-safe form of an install id: its first eight hex characters, or
     * {@code none} when the client sent none. Short enough that a leaked log line
     * cannot be joined back to a row on its own, long enough to follow one
     * registration through a log file.
     */
    static String shortInstallation(UUID installationId) {
        return installationId == null ? "none" : installationId.toString().substring(0, 8);
    }

    /**
     * Logs and counts a registration refused over its country.
     *
     * <p>Counted under its own reason strings rather than as a
     * {@link RegistrationRejectionReason}: that enum is the display-name
     * vocabulary and telemetry maps {@code nickname_rejected} strictly onto it,
     * so a country cause added there would become an accepted nickname reason.
     */
    private void recordCountryRejection(String reason) {
        log.warn("guest registration rejected: reason={}", reason);
        diagnosticCounters.recordGuestRegistrationRejected(reason);
    }

    /**
     * Validates and normalises a player-chosen nickname. Registration and later
     * renames share this so a name that is legal at sign-up can never be illegal
     * afterwards, or the reverse.
     *
     * <p>Defined in terms of {@link #classifyDisplayName}, so the reason code
     * written to the log and the rule that actually rejected the name cannot
     * drift apart. Returns the exact value that is stored and echoed back to the
     * client: NFC-normalised and trimmed.
     *
     * @throws ApiException 400 with the reason-specific message
     */
    private String requireValidDisplayName(String rawDisplayName) {
        RegistrationRejectionReason reason = classifyDisplayName(rawDisplayName);
        if (reason == null) {
            return normalizeDisplayName(rawDisplayName);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, rejectionMessage(reason));
    }

    /**
     * The canonical form of a display name: Unicode NFC, then leading and
     * trailing whitespace removed.
     *
     * <p>NFC first, so that a name typed as a base letter plus a combining accent
     * and the same name typed precomposed become one identical string before
     * anything counts or compares it. The stored value, the validated value and
     * the value returned to the client are all this one.
     *
     * <p>Trimming uses a wider notion of whitespace than {@link String#strip()}:
     * {@code Character.isWhitespace} deliberately excludes the non-breaking
     * spaces, so stripping alone would leave a U+00A0 attached to the name and
     * then reject it. Both categories are stripped here, and any whitespace that
     * survives is by definition internal — which is what
     * {@link RegistrationRejectionReason#WHITESPACE} reports.
     */
    static String normalizeDisplayName(String rawDisplayName) {
        if (rawDisplayName == null) {
            return null;
        }
        String nfc = java.text.Normalizer.normalize(rawDisplayName, java.text.Normalizer.Form.NFC);

        int start = 0;
        int end = nfc.length();
        while (start < end && isUnicodeWhitespace(nfc.codePointAt(start))) {
            start += Character.charCount(nfc.codePointAt(start));
        }
        while (end > start) {
            int previous = nfc.codePointBefore(end);
            if (!isUnicodeWhitespace(previous)) {
                break;
            }
            end -= Character.charCount(previous);
        }
        return nfc.substring(start, end);
    }

    /**
     * Whitespace in the broad sense: everything {@code Character.isWhitespace}
     * accepts (space, tab, line breaks) plus every Unicode space separator, which
     * adds the non-breaking spaces U+00A0 and U+202F and the ideographic space
     * U+3000 that {@code isWhitespace} leaves out.
     */
    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * Which rule a display name breaks, or {@code null} when it is acceptable.
     *
     * <p>Pure and side-effect free, so the rejection path can call it a second
     * time to name the cause without re-running logic that might disagree with
     * itself. Checks run in the order BLANK, WHITESPACE, INVALID_CHARACTER,
     * TOO_SHORT, TOO_LONG — content before size, so a name that is both short and
     * illegal reports the illegal character, which is the more useful complaint.
     *
     * <p>The accepted set is: Unicode letters of any script, Unicode decimal
     * digits, combining marks, and underscore. Combining marks have to be in that
     * list — {@code Character.isLetter} is false for the Devanagari virama and
     * vowel signs, so a name like अर्जुन would otherwise be rejected as an illegal
     * character despite being ordinary text.
     */
    static RegistrationRejectionReason classifyDisplayName(String rawDisplayName) {
        if (rawDisplayName == null) {
            return RegistrationRejectionReason.BLANK;
        }
        String name = normalizeDisplayName(rawDisplayName);
        if (name.isEmpty()) {
            return RegistrationRejectionReason.BLANK;
        }

        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            if (isUnicodeWhitespace(codePoint)) {
                return RegistrationRejectionReason.WHITESPACE;
            }
            if (!isAllowedDisplayNameCodePoint(codePoint)) {
                return RegistrationRejectionReason.INVALID_CHARACTER;
            }
            i += Character.charCount(codePoint);
        }

        // Cheap guard first: a name past the storage bound cannot be inside the
        // grapheme bound either, and this avoids running the break iterator over
        // a pathological input.
        if (name.length() > DISPLAY_NAME_MAX_CHARS) {
            return RegistrationRejectionReason.TOO_LONG;
        }

        int length = graphemeLength(name);
        if (length < DISPLAY_NAME_MIN_LENGTH) {
            return RegistrationRejectionReason.TOO_SHORT;
        }
        if (length > DISPLAY_NAME_MAX_LENGTH) {
            return RegistrationRejectionReason.TOO_LONG;
        }
        return null;
    }

    /** Letters of any script, decimal digits, combining marks, and underscore. */
    private static boolean isAllowedDisplayNameCodePoint(int codePoint) {
        if (codePoint == '_') {
            return true;
        }
        if (Character.isLetter(codePoint) || Character.isDigit(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK          // Mn - Devanagari vowel signs, Arabic harakat
                || type == Character.COMBINING_SPACING_MARK; // Mc - Devanagari matras
    }

    /**
     * Length in user-perceived characters (grapheme clusters), not Java chars and
     * not code points.
     *
     * <p>{@code String.length()} would count a Korean syllable as one but an
     * emoji as two and a Devanagari cluster as three, so it is not a length a
     * player would recognise. Code points fix the emoji but still count each
     * combining mark separately.
     *
     * <p>Limitation worth stating: {@link java.text.BreakIterator} implements the
     * legacy grapheme-cluster rules, not the full UAX #29 extended rules, so it
     * would split some emoji ZWJ sequences that a modern renderer draws as one
     * glyph. That does not affect this validator — emoji and zero-width joiners
     * are rejected as invalid characters before any counting happens — but it
     * would matter if the accepted set ever widened.
     */
    static int graphemeLength(String value) {
        java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        it.setText(value);
        int count = 0;
        while (it.next() != java.text.BreakIterator.DONE) {
            count++;
        }
        return count;
    }

    /** The 400 message for a reason code. */
    private static String rejectionMessage(RegistrationRejectionReason reason) {
        return switch (reason) {
            case BLANK -> "displayName is required";
            case TOO_SHORT, TOO_LONG -> "displayName must be between " + DISPLAY_NAME_MIN_LENGTH
                    + " and " + DISPLAY_NAME_MAX_LENGTH + " characters";
            case WHITESPACE -> "displayName may not contain spaces";
            case INVALID_CHARACTER ->
                    "displayName may only contain letters, digits and underscore";
        };
    }

    /** Logs and counts a refused registration. Never logs the nickname itself. */
    private void recordRejection(RegistrationRejectionReason reason) {
        RegistrationRejectionReason resolved =
                reason == null ? RegistrationRejectionReason.BLANK : reason;
        log.warn("guest registration rejected: reason={}", resolved);
        diagnosticCounters.recordGuestRegistrationRejected(resolved.name());
    }

    @Transactional
    public AccountAvatarResponse updateAvatar(Account account, String rawAvatarId) {
        if (rawAvatarId == null || rawAvatarId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "avatarId is required");
        }
        String trimmed = rawAvatarId.trim();
        if (trimmed.length() > AVATAR_ID_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "avatarId must be at most " + AVATAR_ID_MAX_LENGTH + " characters");
        }
        if (!AVATAR_ID_PATTERN.matcher(trimmed).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "avatarId may only contain letters, numbers, underscore and dash");
        }
        account.setAvatarId(trimmed);
        accountRepository.save(account);
        return new AccountAvatarResponse(account.getId().toString(), account.getAvatarId());
    }

    /** The avatar to show: the chosen avatar, or the default when null/blank. */
    public static String resolveAvatarId(String avatarId) {
        if (avatarId != null && !avatarId.isBlank()) {
            return avatarId.trim();
        }
        return DEFAULT_AVATAR_ID;
    }

    /** Default name for a fresh account: "Agent" + 4 stable chars from the account id. */
    public static String defaultDisplayName(UUID accountId) {
        String suffix = accountId.toString().replace("-", "").substring(0, 4).toUpperCase();
        return "Agent" + suffix;
    }

    /**
     * The name to show for a real player: their chosen display name, or a stable
     * per-account fallback when none was set. Never returns null/blank.
     */
    public static String resolveDisplayName(String displayName, UUID accountId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (accountId == null) {
            return "Oyuncu";
        }
        return defaultDisplayName(accountId);
    }

    /**
     * Resolves the account behind a raw {@code X-Guest-Token} header value,
     * touching its lastSeenAt. Throws 401 if the header is missing, malformed,
     * or does not match an active account.
     */
    @Transactional
    public Account requireAccountByToken(String rawToken) {
        Account account = resolveAndTouch(rawToken);
        playerActivityService.recordActivity(account.getId());
        return account;
    }

    /**
     * Same authentication and lastSeenAt touch as {@link #requireAccountByToken}
     * but without the request-estimated session tracking, so the precise session
     * lifecycle endpoints manage sessions themselves without a competing row.
     */
    @Transactional
    public Account resolveActiveAccount(String rawToken) {
        return resolveAndTouch(rawToken);
    }

    private Account resolveAndTouch(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing X-Guest-Token header");
        }

        UUID token;
        try {
            token = UUID.fromString(rawToken.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid X-Guest-Token");
        }

        Account account = accountRepository.findByGuestToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid X-Guest-Token"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        account.setLastSeenAt(Instant.now());
        return account;
    }
}
