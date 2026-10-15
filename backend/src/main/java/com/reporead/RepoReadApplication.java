package com.reporead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RepoReadProperties.class)
public class RepoReadApplication {
    public static void main(String[] args) {
        SpringApplication.run(RepoReadApplication.class, args);
    }
}
