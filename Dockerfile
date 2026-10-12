FROM eclipse-temurin:21-jdk-noble

# System configurations and performance defaults
ENV SERVER_PORT=9081
ENV SPRING_PROFILES_ACTIVE=prod
ENV LOG4J2_CONFIG=/config/log4j2.xml
# Heap is sized from the container --memory limit set in the Jenkinsfile
ENV MALLOC_ARENA_MAX=2
ENV JAVA_OPTS="-Djava.library.path=/usr/lib/jni:/usr/lib/x86_64-linux-gnu -XX:MaxRAMPercentage=60 -XX:G1PeriodicGCInterval=60000 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/data/royawl/logs -Duser.timezone=Asia/Kolkata"

# Copy execution target layer
COPY target/*.jar royawl-recommendation-service.jar

# Enforce explicit application property overrides via System Properties (-D)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} -Dlog4j2.configurationFile=${LOG4J2_CONFIG} ${KAFKA_OPTS} -jar /royawl-recommendation-service.jar"]