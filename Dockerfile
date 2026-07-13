FROM eclipse-temurin:21-jdk-noble

# System configurations and performance defaults
ENV SERVER_PORT=9081
ENV SPRING_PROFILES_ACTIVE=prod
ENV LOG4J2_CONFIG=/config/log4j2.xml
ENV JAVA_OPTS="-Djava.library.path=/usr/lib/jni:/usr/lib/x86_64-linux-gnu -Xmx4g -Duser.timezone=Asia/Kolkata"

# Copy execution target layer
COPY target/*.jar royawl-recommendation-service.jar

# Enforce explicit application property overrides via System Properties (-D)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${SERVER_PORT} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} -Dlog4j2.configurationFile=${LOG4J2_CONFIG} ${KAFKA_OPTS} -jar /royawl-recommendation-service.jar"]