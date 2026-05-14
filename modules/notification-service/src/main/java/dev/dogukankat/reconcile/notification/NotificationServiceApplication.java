package dev.dogukankat.reconcile.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafkaRetryTopic
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NotificationServiceApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
