package com.example.hotel.config;

import com.example.hotel.common.i18n.PmsLocaleResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * PMS UI internationalization: a cookie-backed locale limited to Vietnamese (default) and English,
 * switched explicitly with {@code ?lang=vi} or {@code ?lang=en}.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    private final boolean secureCookie;
    private final String defaultLanguage;

    /**
     * Creates the configuration.
     *
     * @param secureCookie {@code hotel.i18n.cookie-secure}: restrict the language cookie to HTTPS
     * @param defaultLanguage {@code hotel.i18n.default-locale}: language without a valid cookie
     *     ({@code vi} at runtime; the test environment sets {@code en})
     */
    public I18nConfig(
            @Value("${hotel.i18n.cookie-secure:false}") boolean secureCookie,
            @Value("${hotel.i18n.default-locale:vi}") String defaultLanguage) {
        this.secureCookie = secureCookie;
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Provides the locale resolver. The bean name must be {@code localeResolver}.
     *
     * @return the PMS cookie locale resolver
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new PmsLocaleResolver(secureCookie, java.util.Locale.forLanguageTag(defaultLanguage));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor);
    }
}
