FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.49.0-jammy AS browsers

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
      libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
      libgbm1 libasound2 libpango-1.0-0 libcairo2 libx11-6 libx11-xcb1 \
      libxcb1 libxext6 libxfixes3 fonts-liberation ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=browsers /ms-playwright/chromium-1148 /ms-playwright/chromium-1148
COPY --from=browsers /ms-playwright/chromium_headless_shell-1148 /ms-playwright/chromium_headless_shell-1148
COPY --from=build /app/target/open-ai-api-*.jar /app/app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=18080 \
    UPSTREAM_HEADLESS=true \
    UPSTREAM_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
