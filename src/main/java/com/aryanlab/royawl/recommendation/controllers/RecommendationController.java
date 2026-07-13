package com.aryanlab.royawl.recommendation.controllers;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aryanlab.royawl.recommendation.services.RecommendationService;

@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

	private static final Logger logger = LoggerFactory.getLogger(RecommendationController.class);

	@Autowired
	private RecommendationService recommendationService;

	@GetMapping("/{userId}")
	public ResponseEntity<List<Long>> recommend(@PathVariable long userId,
			@RequestParam(name = "limit", required = true) Integer limit,
			@RequestParam(name = "offset", required = true) Integer offset,
			@RequestParam(name = "type", required = true) int type) {
		try {
			List<Long> postIds = recommendationService.getRecommendationsForUser(type, userId, offset, limit);
			return ResponseEntity.ok(postIds);

		} catch (Exception e) {
			logger.error("Error getting recommendations for user {}: ", userId, e);
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
		}
	}

	@GetMapping("/healthCheck")
	public ResponseEntity<?> healthCheck() {
		logger.info("\n=========== Health check endpoint called ===========\n");
		return ResponseEntity.ok(null);

	}
}
