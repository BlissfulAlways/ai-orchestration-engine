package com.orchestrator.ai_orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Gives scheduled jobs (task polling, heartbeats, stuck-task rescue, job recovery) several
 * threads. Spring's default is ONE thread, which means a slow scheduled method blocks every
 * other scheduled method, including the one that exists to rescue stuck work.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${orchestrator.scheduler.pool-size:6}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduler-");
        // On shutdown, let a running scheduled method finish (up to 20 s) instead of cutting it off.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}