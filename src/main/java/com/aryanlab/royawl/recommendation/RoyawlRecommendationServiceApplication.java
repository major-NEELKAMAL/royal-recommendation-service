package com.aryanlab.royawl.recommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.PropertySource;

@EnableDiscoveryClient
@SpringBootApplication
@PropertySource({ "classpath:application-${spring.profiles.active}.properties" })
public class RoyawlRecommendationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RoyawlRecommendationServiceApplication.class, args);
	}

}
