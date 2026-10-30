FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Kolkata
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" PYTHON=python
WORKDIR /app
COPY scripts/requirements.txt scripts/requirements.txt
RUN pip install --no-cache-dir -r scripts/requirements.txt
COPY scripts/fetch_bars.py scripts/fetch_bars.py
COPY --from=build /src/target/stock-alert-bot.jar /app/stock-alert-bot.jar
VOLUME ["/app/data", "/app/reports"]
ENTRYPOINT ["java", "-jar", "/app/stock-alert-bot.jar"]
CMD ["live"]
