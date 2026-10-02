package com.example.hotel.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures the business-time clock used by the Dashboard's hotel-local date/time semantics. */
@Configuration
public class DashboardClockConfiguration {

    private static final ZoneId DASHBOARD_BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Provides the production clock using the approved Dashboard business timezone.
     *
     * @return a clock backed by the system instant in Asia/Ho_Chi_Minh
     */
    @Bean
    Clock dashboardClock() {
        return Clock.system(DASHBOARD_BUSINESS_ZONE);
    }
}
