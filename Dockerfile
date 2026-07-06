FROM eclipse-temurin:21-jdk-noble

# Default values if not provided during docker run
ENV SERVER_PORT=9081
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-Djava.library.path=/usr/lib/jni:/usr/lib/x86_64-linux-gnu -Xmx4g -Duser.timezone=Asia/Kolkata"

COPY target/*.jar royawl-recommendation-service.jar

# Dynamically assign Port and Profile at runtime
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dlog4j2.configurationFile=${LOG4J2_CONFIG} -jar /royawl-recommendation-service.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE} --server.port=${SERVER_PORT} ${KAFKA_OPTS}"]