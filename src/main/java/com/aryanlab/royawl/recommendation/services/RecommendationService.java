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
		logger.info("Loading ALS model and metadata from {}", basePath);

		try {
			this.alsModel = ALSModel.load(basePath + "/als-model");
			this.userIndexerModel = StringIndexerModel.load(basePath + "/user-indexer");
			// Load and cache locally to ensure microsecond lookup latency
			this.postMetadataDF = spark.read().parquet(basePath + "/post-metadata").cache();

			logger.info("✅ ALS Model and Metadata loaded successfully");
		} catch (Exception e) {
			logger.error("❌ Failed to load model or metadata from {}", basePath, e);
			this.alsModel = null;
			this.userIndexerModel = null;
			this.postMetadataDF = null;
		}
	}

	public synchronized void reloadModel() {
		loadModel();
	}

	/**
	 * @param postTypeInt Pass the smallint numeric identifier (e.g. Image value vs
	 *                    Video value)
	 */
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

		if (indexedUserDF.limit(1).count() == 0) {
			logger.info("User {} not found in trained index.", userId);
			return Collections.emptyList();
		}

		// 3. Request highly localized vector matches (e.g. top 500 items)
		int fetchK = Math.max(500, (offset + limit) * 3);
		Dataset<Row> recsDF = alsModel.recommendForUserSubset(indexedUserDF, fetchK);

		// 4. Structural Extraction
		Dataset<Row> exploded = recsDF.select(explode(col("recommendations")).alias("rec"))
				.select(col("rec.postIndexInt").alias("postIndexInt"), col("rec.rating").alias("score"));

		// 5. In-Memory Broadcast Join against the isolated 500 recommendation records
		Dataset<Row> decodedDF = exploded
				.join(functions.broadcast(postMetadataDF),
						exploded.col("postIndexInt").equalTo(postMetadataDF.col("id")))
				.select(col("post_id_str"), col("post_type"), col("score"));

		// 6. Pagination & Selection via SQL View
		decodedDF.createOrReplaceTempView("user_recs");
		String sql = String.format("SELECT CAST(post_id_str AS LONG) as post_id FROM user_recs "
				+ "WHERE post_type = %d " + "ORDER BY score DESC LIMIT %d OFFSET %d", postTypeInt, limit, offset);

		Dataset<Row> paged = spark.sql(sql);

		return paged.select("post_id").as(Encoders.LONG()).collectAsList();

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
