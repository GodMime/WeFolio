package com.jxc.wefolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WefolioJavaRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WefolioJavaRuntimeApplication.class, args);
    }

}
