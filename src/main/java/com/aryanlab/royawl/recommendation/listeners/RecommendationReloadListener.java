package com.aryanlab.royawl.recommendation.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.aryanlab.royawl.recommendation.services.RecommendationService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RecommendationReloadListener {

	private final RecommendationService recommendationService;
	private static final Logger log = LoggerFactory.getLogger(RecommendationReloadListener.class);

	@KafkaListener(
		    topics = "${app.kafka.topic.recommendation-reload}", 
		    groupId = "${app.kafka.consumer.group.recommendation-reload}-${spring.application.name}-${HOSTNAME:unknown}"
		)
	public void handleReload(String message, Acknowledgment ack) {

		log.info("Received reload command on instance {}: {}",
				System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "unknown", message);

		try {
			recommendationService.reloadModel();
			ack.acknowledge();
			log.info("✅ Model reloaded successfully on this instance");
		} catch (Exception e) {
			log.error("Failed to reload model on this instance", e);
		}
	}
}
