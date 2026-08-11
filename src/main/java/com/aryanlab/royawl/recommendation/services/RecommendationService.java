package com.aryanlab.royawl.recommendation.services;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.explode;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.spark.ml.feature.StringIndexerModel;
import org.apache.spark.ml.recommendation.ALSModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aryanlab.royawl.recommendation.configs.ApplicationProperties;

@Service
public class RecommendationService {

	private static final Logger logger = LoggerFactory.getLogger(RecommendationService.class);

	private volatile ALSModel alsModel;
	private volatile StringIndexerModel userIndexerModel;
	private volatile Dataset<Row> postMetadataDF;

	@Autowired
	private ApplicationProperties applicationProperties;

	@Autowired
	private SparkSession spark;

	@PostConstruct
	public void init() {
		logger.info("RecommendationService initialized using shared SparkSession bean.");
		loadModel();
	}

	private void loadModel() {
		String basePath = applicationProperties.getRecommendationModelPath();
		try {
			SparkSession isolatedSession = spark.newSession();

			ALSModel newAlsModel = ALSModel.load(basePath + "/als-model");
			StringIndexerModel newUserIndexerModel = StringIndexerModel.load(basePath + "/user-indexer");
			
			Dataset<Row> newPostMetadataDF = isolatedSession.read().parquet(basePath + "/post-metadata").cache();
			newPostMetadataDF.count(); // Forces read

			if (this.postMetadataDF != null) {
				try { this.postMetadataDF.unpersist(false); } catch (Exception e) {}
			}

			this.alsModel = newAlsModel;
			this.userIndexerModel = newUserIndexerModel;
			this.postMetadataDF = newPostMetadataDF;

			logger.info("✅ ALS Model and Enriched Metadata safely hot-swapped in memory.");
		} catch (Exception e) {
			logger.error("❌ Failed hot-swapping new model; retaining previous stable state.", e);
		}
	}

	public synchronized void reloadModel() {
		loadModel();
	}

	public List<Long> getRecommendationsForUser(int postTypeInt, long userId, int offset, int limit) {

		if (alsModel == null || userIndexerModel == null || postMetadataDF == null) {
			logger.warn("Model or metadata packages not fully loaded for user {}", userId);
			return Collections.emptyList();
		}

		// 1. Prepare Single Row DataFrame for Input
		Dataset<Row> rawUserDF = spark.createDataFrame(Collections.singletonList(new UserId(userId)), UserId.class)
				.withColumn("user_id_str", col("userId").cast(DataTypes.StringType));

		// 2. Map string to internal Spark User Index
		Dataset<Row> indexedUserDF = userIndexerModel.transform(rawUserDF).withColumn("userIndexInt",
				col("userIndex").cast(DataTypes.IntegerType));

		// Check if user exists in the trained index model
		boolean userExists = true;
		try {
			if (indexedUserDF.limit(1).count() == 0 || indexedUserDF.select("userIndex").first().isNullAt(0)) {
				userExists = false;
			}
		} catch (Exception e) {
			userExists = false;
		}

		// 3. COLD-START FALLBACK: If user is missing, serve trending/latest content seamlessly
		if (!userExists) {
			logger.info("User {} not found in trained index. Serving fallback recommendations.", userId);
			return getFallbackRecommendations(postTypeInt, offset, limit, "trending"); // Change to "latest" if preferred
		}

		try {
			// 4. Request highly localized vector matches
			int fetchK = Math.max(500, (offset + limit) * 3);
			Dataset<Row> recsDF = alsModel.recommendForUserSubset(indexedUserDF, fetchK);

			if (recsDF.count() == 0) {
				return getFallbackRecommendations(postTypeInt, offset, limit, "trending");
			}

			// 5. Structural Extraction
			Dataset<Row> exploded = recsDF.select(explode(col("recommendations")).alias("rec"))
					.select(col("rec.postIndexInt").alias("postIndexInt"), col("rec.rating").alias("score"));

			// 6. In-Memory Broadcast Join
			Dataset<Row> decodedDF = exploded
					.join(functions.broadcast(postMetadataDF),
							exploded.col("postIndexInt").equalTo(postMetadataDF.col("id")))
					.select(col("post_id_str"), col("post_type"), col("score"));

			// 7. Pagination & Selection via SQL View
			decodedDF.createOrReplaceTempView("user_recs");
			String sql = String.format("SELECT CAST(post_id_str AS LONG) as post_id FROM user_recs "
					+ "WHERE post_type = %d ORDER BY score DESC LIMIT %d OFFSET %d", postTypeInt, limit, offset);

			List<Long> results = spark.sql(sql).select("post_id").as(Encoders.LONG()).collectAsList();

			if (results.isEmpty()) {
				return getFallbackRecommendations(postTypeInt, offset, limit, "trending");
			}

			return results;

		} catch (Exception e) {
			logger.error("Error fetching recommendations for user {}, falling back.", userId, e);
			return getFallbackRecommendations(postTypeInt, offset, limit, "trending");
		}
	}

	/**
	 * Fallback helper method supporting sorting strategies: "trending" or "latest"
	 */
	private List<Long> getFallbackRecommendations(int postTypeInt, int offset, int limit, String strategy) {
		postMetadataDF.createOrReplaceTempView("fallback_metadata");
		
		String orderByClause = "popularity_score DESC";
		if ("latest".equalsIgnoreCase(strategy)) {
			orderByClause = "created_at DESC";
		}

		String fallbackSql = String.format(
			"SELECT CAST(post_id_str AS LONG) as post_id FROM fallback_metadata " +
			"WHERE post_type = %d ORDER BY %s LIMIT %d OFFSET %d", 
			postTypeInt, orderByClause, limit, offset
		);
		
		return spark.sql(fallbackSql).select("post_id").as(Encoders.LONG()).collectAsList();
	}

	public static class UserId implements Serializable {
		private static final long serialVersionUID = 1L;
		private long userId;

		public UserId(long userId) {
			this.userId = userId;
		}

		public long getUserId() {
			return userId;
		}

		public void setUserId(long userId) {
			this.userId = userId;
		}
	}
}