package com.jxc.wefolio.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

@RestController
public class VersionController {

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ));
    }

    @GetMapping("/api/version")
    public ResponseEntity<Map<String, String>> version() {
        String version = "unknown";
        String buildTime = "unknown";

        try {
            ClassPathResource resource = new ClassPathResource("version.properties");
            try (InputStream is = resource.getInputStream()) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("build.version", "unknown");
                buildTime = props.getProperty("build.time", "unknown");
            }
        } catch (Exception e) {
            // fallback to unknown
        }

        return ResponseEntity.ok(Map.of(
                "version", version,
                "buildTime", buildTime
        ));
    }
}
