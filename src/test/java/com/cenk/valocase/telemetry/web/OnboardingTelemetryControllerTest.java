package com.cenk.valocase.telemetry.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.common.exception.GlobalExceptionHandler;
import com.cenk.valocase.telemetry.TelemetryProperties;
import com.cenk.valocase.telemetry.domain.OnboardingEvent;
import com.cenk.valocase.telemetry.repository.OnboardingEventRepository;
import com.cenk.valocase.telemetry.service.InstallationRateLimiter;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService;

/**
 * The HTTP contract of the telemetry endpoint: real controller, real filter, real
 * exception handler, real service. Only the repository is mocked, so no database
 * is needed and the status codes are the ones a client would actually receive.
 */
class OnboardingTelemetryControllerTest {

    private MockMvc mvc;
    private TelemetryProperties properties;
    private DiagnosticCounters counters;
    private final List<OnboardingEvent> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new TelemetryProperties();
        counters = new DiagnosticCounters();

        OnboardingEventRepository repository = mock(OnboardingEventRepository.class);
        when(repository.existsByEventId(anyString())).thenAnswer(inv ->
                stored.stream().anyMatch(e -> e.getEventId().equals(inv.getArgument(0))));
        when(repository.saveAndFlush(any(OnboardingEvent.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        OnboardingTelemetryService service = new OnboardingTelemetryService(
                repository, new InstallationRateLimiter(properties), counters, properties, txManager);

        mvc = MockMvcBuilders
                .standaloneSetup(new OnboardingTelemetryController(service, properties))
                .addFilters(new OnboardingBodySizeFilter(properties, counters))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MvcResult send(String json) throws Exception {
        return mvc.perform(post("/api/v1/telemetry/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8))).andReturn();
    }

    private static String event(String name, String eventId) {
        return """
               {"installationId":"install-1","eventName":"%s","eventId":"%s",
                "clientTimestampUtc":"2026-08-03T10:00:00Z","appVersion":"1.0.19",
                "platform":"ANDROID"}""".formatted(name, eventId);
    }

    @Test
    void everyAllowlistedEventIsAccepted() throws Exception {
        String[] names = {
                "app_launched", "fan_notice_shown", "fan_notice_accepted",
                "nickname_screen_shown", "nickname_rejected", "nickname_confirm_clicked",
                "registration_attempted", "registration_failed", "registration_succeeded",
        };
        for (int i = 0; i < names.length; i++) {
            MvcResult result = send(event(names[i], "evt-" + i));
            assertEquals(202, result.getResponse().getStatus(), names[i]);
            assertTrue(result.getResponse().getContentAsString().contains("accepted"), names[i]);
        }
        assertEquals(names.length, stored.size());
    }

    @Test
    void anUnknownEventNameIs400() throws Exception {
        assertEquals(400, send(event("app_opened", "evt-1")).getResponse().getStatus());
        assertTrue(stored.isEmpty());
    }

    @Test
    void aDuplicateEventIdIs202NotAnError() throws Exception {
        assertEquals(202, send(event("app_launched", "evt-dup")).getResponse().getStatus());

        MvcResult second = send(event("app_launched", "evt-dup"));
        // 202 on purpose: reporting an error here would push clients into the
        // retry loop this endpoint is meant to survive.
        assertEquals(202, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("duplicate"));
        assertEquals(1, stored.size());
    }

    @Test
    void aMissingInstallationIdIs400() throws Exception {
        String json = """
                      {"eventName":"app_launched","eventId":"evt-1","appVersion":"1.0.19"}""";
        assertEquals(400, send(json).getResponse().getStatus());
        assertTrue(stored.isEmpty());
    }

    @Test
    void anOversizedBodyIs400AndIsNeverParsed() throws Exception {
        properties.setMaxBodyBytes(256);
        String json = """
                      {"installationId":"install-1","eventName":"app_launched","eventId":"evt-1",
                       "appVersion":"1.0.19","platform":"ANDROID","padding":"%s"}"""
                .formatted("A".repeat(4_000));

        assertEquals(400, send(json).getResponse().getStatus());
        assertTrue(stored.isEmpty());
        assertEquals(1L, counters.telemetryRejectionsByReason().get("BODY_TOO_LARGE"));
    }

    @Test
    void aBodyJustUnderTheLimitStillWorks() throws Exception {
        properties.setMaxBodyBytes(2_048);
        assertEquals(202, send(event("app_launched", "evt-1")).getResponse().getStatus());
        assertEquals(1, stored.size());
    }

    @Test
    void rateLimitingReturns429() throws Exception {
        properties.setRateLimitEvents(3);

        for (int i = 0; i < 3; i++) {
            assertEquals(202, send(event("app_launched", "evt-" + i)).getResponse().getStatus());
        }
        assertEquals(429, send(event("app_launched", "evt-over")).getResponse().getStatus());
        assertEquals(3, stored.size());
    }

    @Test
    void forbiddenExtraFieldsAreSilentlyDroppedNotStored() throws Exception {
        // A buggy or malicious client sending a nickname, a token, an ad id and an
        // email. The request still succeeds — rejecting would break forward
        // compatibility — but none of it may survive anywhere.
        String json = """
                      {"installationId":"install-1","eventName":"nickname_rejected","eventId":"evt-1",
                       "appVersion":"1.0.19","platform":"ANDROID","rejectionReason":"WHITESPACE",
                       "nickname":"Ahmet Yilmaz","guestToken":"11111111-2222-3333-4444-555555555555",
                       "advertisingId":"ad-123","email":"a@b.com","ipAddress":"1.2.3.4",
                       "deviceModel":"Pixel 9","arbitrary":{"nested":"value"}}""";

        assertEquals(202, send(json).getResponse().getStatus());
        assertEquals(1, stored.size());

        OnboardingEvent event = stored.get(0);
        String persisted = String.join("|",
                String.valueOf(event.getEventId()), String.valueOf(event.getInstallationId()),
                String.valueOf(event.getEventName()), String.valueOf(event.getAppVersion()),
                String.valueOf(event.getPlatform()), String.valueOf(event.getRejectionReason()),
                String.valueOf(event.getNetworkErrorCategory()));

        assertFalse(persisted.contains("Ahmet"), persisted);
        assertFalse(persisted.contains("11111111"), persisted);
        assertFalse(persisted.contains("ad-123"), persisted);
        assertFalse(persisted.contains("a@b.com"), persisted);
        assertFalse(persisted.contains("1.2.3.4"), persisted);
        assertFalse(persisted.contains("Pixel"), persisted);
        // The one legitimate optional field did survive.
        assertEquals("WHITESPACE", event.getRejectionReason());
    }

    @Test
    void malformedJsonIsRejectedNotStored() throws Exception {
        MvcResult result = send("{\"installationId\": ");
        assertTrue(result.getResponse().getStatus() >= 400);
        assertTrue(stored.isEmpty());
    }

    @Test
    void anEmptyBodyIs400() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/telemetry/onboarding")
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertTrue(result.getResponse().getStatus() >= 400);
        assertTrue(stored.isEmpty());
    }

    @Test
    void theEndpointCanBeTurnedOffEntirely() throws Exception {
        properties.setEnabled(false);
        assertEquals(404, send(event("app_launched", "evt-1")).getResponse().getStatus());
        assertTrue(stored.isEmpty());
    }

    @Test
    void anIngestionFailureIsStill202SoAClientNeverBlocksOnTelemetry() throws Exception {
        OnboardingEventRepository broken = mock(OnboardingEventRepository.class);
        when(broken.existsByEventId(anyString())).thenThrow(new IllegalStateException("db down"));
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        MockMvc failing = MockMvcBuilders
                .standaloneSetup(new OnboardingTelemetryController(
                        new OnboardingTelemetryService(broken, new InstallationRateLimiter(properties),
                                counters, properties, txManager),
                        properties))
                .addFilters(new OnboardingBodySizeFilter(properties, counters))
                .build();

        MvcResult result = failing.perform(post("/api/v1/telemetry/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content(event("app_launched", "evt-1"))).andReturn();

        // The whole point: a telemetry outage must not surface to the player as a
        // failure they could mistake for a registration problem.
        assertEquals(202, result.getResponse().getStatus());
    }

    @Test
    void theFilterLeavesOtherEndpointsAlone() throws Exception {
        // The body limit is scoped to the telemetry path; applying it globally
        // would break legitimately large gameplay payloads.
        OnboardingBodySizeFilter filter = new OnboardingBodySizeFilter(properties, counters);
        jakarta.servlet.http.HttpServletRequest gameplay =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/inventory/sell");
        jakarta.servlet.http.HttpServletRequest telemetry =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/telemetry/onboarding");

        assertTrue(invokeShouldNotFilter(filter, gameplay));
        assertFalse(invokeShouldNotFilter(filter, telemetry));
    }

    private static boolean invokeShouldNotFilter(OnboardingBodySizeFilter filter,
                                                 jakarta.servlet.http.HttpServletRequest request)
            throws Exception {
        var method = OnboardingBodySizeFilter.class
                .getDeclaredMethod("shouldNotFilter", jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(filter, request);
    }
}
