package com.aryanlab.royawl.recommendation.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.Getter;

@Service
@Getter
public class ApplicationProperties {
  private static final Logger logger = LoggerFactory.getLogger(ApplicationProperties.class);



  @Value("${application.recommendation.model.path}")
  private String recommendationModelPath;



  @PostConstruct
  public void logProperties() {

    try {
      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      String json = mapper.writeValueAsString(this);
      logger.info("Application Properties Loaded :\n{}", json);
    } catch (Exception e) {
      logger.error("Error logging application properties ", e);
    }

  }

}
