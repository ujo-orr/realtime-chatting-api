package com.example.realtimechattingapi.websocket;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SocketSchedulerConfig {
    @Bean
    ThreadPoolTaskScheduler socketScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("socket-timer-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
