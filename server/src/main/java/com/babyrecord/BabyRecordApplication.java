package com.babyrecord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BabyRecordApplication {
    public static void main(String[] args) {
        SpringApplication.run(BabyRecordApplication.class, args);
    }
}
