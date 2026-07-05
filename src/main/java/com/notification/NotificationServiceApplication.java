package com.notification;

import com.notification.config.NotificationProperties;
import com.notification.tui.NotificationTui;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.Arrays;
import java.util.Properties;

@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties.class)
@EnableScheduling
public class NotificationServiceApplication {
    public static void main(String[] args) {
        boolean tuiMode = Arrays.stream(args)
                .anyMatch(a -> a.equalsIgnoreCase("tui") || a.equalsIgnoreCase("--tui"));
        if (tuiMode) {
            runTui(args);
        } else {
            SpringApplication.run(NotificationServiceApplication.class, args);
        }
    }

    /**
     * TUI mode: boot the full context (embedded Postgres + services) with the web server off and
     * console logging silenced (logs go to tui.log) so Lanterna can own the terminal, then hand
     * control to the dashboard. Closing the context stops embedded Postgres cleanly.
     */
    private static void runTui(String[] args) {
        Properties props = new Properties();
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("logging.threshold.console", "OFF"); // no console noise; TUI owns the screen
        props.put("logging.file.name", "tui.log");

        SpringApplication app = new SpringApplication(NotificationServiceApplication.class);
        app.setDefaultProperties(props);
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            ctx.getBean(NotificationTui.class).run();
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** System clock; overridable in tests for deterministic FDCPA-window / suppression behavior. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
