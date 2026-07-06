package com.aryanlab.royawl.recommendation.configs;

import org.apache.spark.sql.SparkSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SparkConfig {

  @Bean(destroyMethod = "stop") // Ensures Spark stops cleanly when Spring Boot shuts down
  public SparkSession sparkSession() {
    return SparkSession.builder().appName("RoyaWLSparkEngine").master("local[*]") // Run locally
                                                                                  // using all
                                                                                  // available cores
        .config("spark.ui.enabled", "false")
        // Core fix for thread safety: forces cross-thread inheritance of session state
        .config("spark.sql.legacy.sessionInitWithDefaults", "true").getOrCreate();
  }
}
