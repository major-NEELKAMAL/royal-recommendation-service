package com.aryanlab.royawl.recommendation.configs;

import org.apache.spark.sql.SparkSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SparkConfig {

	@Bean(destroyMethod = "stop") // Ensures Spark stops cleanly when Spring Boot shuts down
	public SparkSession sparkSession() {
		return SparkSession.builder().appName("RoyaWLSparkEngine")
				// Two worker threads: every concurrent task needs its own execution memory
				.master("local[2]")
				.config("spark.ui.enabled", "false")
				// Spark keeps job/stage/query history in memory even with the UI disabled
				.config("spark.ui.retainedJobs", "50")
				.config("spark.ui.retainedStages", "50")
				.config("spark.sql.ui.retainedExecutions", "50")
				// Default of 200 shuffle partitions is sized for clusters, not a single local JVM
				.config("spark.sql.shuffle.partitions", "8")
				// Core fix for thread safety: forces cross-thread inheritance of session state
				.config("spark.sql.legacy.sessionInitWithDefaults", "true").getOrCreate();
	}
}
