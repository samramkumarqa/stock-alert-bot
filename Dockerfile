FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Kolkata
WORKDIR /app
COPY --from=build /src/target/stock-alert-bot.jar /app/stock-alert-bot.jar
VOLUME ["/app/data", "/app/reports"]
ENTRYPOINT ["java", "-jar", "/app/stock-alert-bot.jar"]
CMD ["live"]
