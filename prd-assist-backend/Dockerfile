# 多阶段构建：第一阶段使用 Maven 打包
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 第二阶段：轻量运行时 (Eclipse Temurin JRE 17)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 安装基础字体库以支持 PlantUML 图表渲染
RUN apt-get update && apt-get install -y --no-install-recommends \
    fontconfig \
    fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/AiDocumentPlatform-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8080
EXPOSE 8080

# 容器化参数：适配 Render 容器内存（使用最多 75% 容器可用内存）
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
