package com.example.autoschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AutoscheduleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoscheduleApplication.class, args);
    }
}
