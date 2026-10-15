package com.aryanlab.royawl.recommendation.services;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.explode;

import java.io.Serializable;
import java.util.ArrayList;
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

	// ModelTrainingJob gives posts with no interactions this id in post-metadata
	private static final long COLD_POST_ID = -1L;

	// Every 4th slot of a personalised feed is a zero-interaction post (while any are left)
	private static final int COLD_POST_INTERVAL = 4;

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

			// 7. Every ALS match of this type, best first (pagination happens after the blend)
			List<Long> alsPostIds = decodedDF.filter(col("post_type").equalTo(postTypeInt))
					.orderBy(col("score").desc())
					.select(col("post_id_str").cast(DataTypes.LongType))
					.as(Encoders.LONG()).collectAsList();

			// 8. Blend in posts nobody has interacted with yet: ALS never scores them (id = -1),
			// so without this they could never reach a feed and never get a first interaction
			List<Long> results = paginate(blendColdPosts(alsPostIds, getColdPostIds(postTypeInt)), offset, limit);

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
	 * Posts of a type with no interactions at training time (id = -1 in the
	 * metadata), newest first.
	 */
	private List<Long> getColdPostIds(int postTypeInt) {
		try {
			return postMetadataDF
					.filter(col("post_type").equalTo(postTypeInt).and(col("id").equalTo(COLD_POST_ID)))
					.orderBy(col("created_at").desc())
					.select(col("post_id_str").cast(DataTypes.LongType))
					.as(Encoders.LONG()).collectAsList();
		} catch (Exception e) {
			logger.error("Failed to read zero-interaction posts from metadata", e);
			return Collections.emptyList();
		}
	}

	/**
	 * Puts one cold post after every COLD_POST_INTERVAL - 1 ALS posts; whatever is
	 * left of either list follows in order.
	 */
	private static List<Long> blendColdPosts(List<Long> alsPostIds, List<Long> coldPostIds) {
		List<Long> blended = new ArrayList<>(alsPostIds.size() + coldPostIds.size());
		int als = 0;
		int cold = 0;
		while (als < alsPostIds.size() || cold < coldPostIds.size()) {
			boolean coldSlot = (blended.size() + 1) % COLD_POST_INTERVAL == 0;
			if (cold < coldPostIds.size() && (coldSlot || als >= alsPostIds.size())) {
				blended.add(coldPostIds.get(cold++));
			} else {
				blended.add(alsPostIds.get(als++));
			}
		}
		return blended;
	}

	private static List<Long> paginate(List<Long> postIds, int offset, int limit) {
		if (offset >= postIds.size()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(postIds.subList(offset, Math.min(postIds.size(), offset + limit)));
	}

	/**
	 * Fallback helper method supporting sorting strategies: "trending" or "latest"
	 */
	private List<Long> getFallbackRecommendations(int postTypeInt, int offset, int limit, String strategy) {
	    try {
	        String sortColumn = "popularity_score";
	        boolean ascending = false;
	        if ("latest".equalsIgnoreCase(strategy)) {
	            sortColumn = "created_at";
	            ascending = false;
	        }

	        // 1. Filter by post type using native DataFrame API
	        Dataset<Row> filtered = postMetadataDF.filter(col("post_type").equalTo(postTypeInt));

	        // 2. Sort natively by the chosen strategy column
	        // Newest first among equal scores, so zero-interaction posts aren't stuck in arbitrary order
	        Dataset<Row> sorted = filtered.sort(ascending ? col(sortColumn).asc() : col(sortColumn).desc(),
	                col("created_at").desc());

	        // 3. Extract post_id_str, cast to Long, and apply pagination natively via limit/collect
	        // (For small-to-medium metadata lists, collecting limited rows safely avoids catalog mapping errors)
	        List<Row> rows = sorted.select(col("post_id_str").cast(DataTypes.LongType).alias("post_id"))
	                               .offset(offset)
	                               .limit(limit)
	                               .collectAsList();

	        List<Long> resultIds = new java.util.ArrayList<>();
	        for (Row row : rows) {
	            if (!row.isNullAt(0)) {
	                resultIds.add(row.getLong(0));
	            }
	        }
	        return resultIds;

	    } catch (Exception e) {
	        logger.error("Failed to execute fallback recommendations via native DataFrame API", e);
	        return Collections.emptyList();
	    }
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