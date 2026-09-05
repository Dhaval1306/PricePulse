package com.pricepulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PricePulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricePulseApplication.class, args);
    }
}
