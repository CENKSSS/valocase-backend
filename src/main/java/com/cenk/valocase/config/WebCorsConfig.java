package com.cenk.valocase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for Unity development clients (editor Play mode, WebGL test builds, and
 * local tooling like Postman/browsers).
 *
 * Note: CORS is a browser-enforced mechanism. Native Unity iOS/Android builds
 * use UnityWebRequest, which is not subject to CORS, so this config has no
 * effect there — it exists so editor/WebGL/browser testing works without being
 * blocked. It uses no credentials (auth is the X-Guest-Token header, not
 * cookies).
 *
 * Allowed origins come from {@code valocase.cors.allowed-origins} (comma
 * separated). It defaults to "*" so local development stays permissive;
 * production sets it from the CORS_ALLOWED_ORIGINS environment variable.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Value("${valocase.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
