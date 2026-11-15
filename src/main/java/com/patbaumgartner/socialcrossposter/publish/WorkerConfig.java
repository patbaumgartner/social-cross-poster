package com.patbaumgartner.socialcrossposter.publish;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Binds the durable retry worker settings (APP_WORKER_*, ADR-015) and enables scheduling
 * for the {@link DeliveryWorker} (UC-07).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

}
