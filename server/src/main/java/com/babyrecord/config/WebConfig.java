package com.babyrecord.config;

import com.babyrecord.auth.DeviceAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final DeviceAuthInterceptor deviceAuthInterceptor;
    private final List<String> allowedOriginPatterns;

    public WebConfig(DeviceAuthInterceptor deviceAuthInterceptor,
                     @Value("${app.cors.allowed-origin-patterns}") List<String> allowedOriginPatterns) {
        this.deviceAuthInterceptor = deviceAuthInterceptor;
        this.allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deviceAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/babies/**",
                        "/api/v1/family/**",
                        "/api/v1/auth/family/create/confirm"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
