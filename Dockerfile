# Multi-stage build for Kotlin/Ktor application

# Stage 1: Build
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for better layer caching
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Copy module build files
COPY app/build.gradle.kts ./app/
COPY sequencer/build.gradle.kts ./sequencer/

# Download dependencies (cached if build files don't change)
RUN gradle dependencies --no-daemon

# Copy source code for all modules
COPY app ./app
COPY sequencer ./sequencer

# Build the application (skip tests for faster builds)
RUN gradle :app:shadowJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/app/build/libs/*-all.jar app.jar

# Expose application port
EXPOSE 8088

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
