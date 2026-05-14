package dev.dogukankat.reconcile.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Web context is intentionally on — it serves the actuator's
 * {@code /actuator/prometheus} scrape endpoint on port 8081 and
 * nothing else. The work of this service is Kafka consumption.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
