# 多阶段构建模板对齐 qc-robot Dockerfile.service
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/logs && chown -R app:app /app
COPY --from=build /build/robot-scheduler-boot/target/*.jar /app/robot-scheduler.jar
USER app
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/robot-scheduler.jar"]
