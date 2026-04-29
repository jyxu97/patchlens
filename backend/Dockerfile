# ---- Stage 1: Build ----
# Use full JDK to compile and package the app with Maven
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy Maven wrapper and pom first so dependency layer is cached separately
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Copy source and build the jar, skipping tests (tests run in CI, not in image build)
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ---- Stage 2: Run ----
# Use slim JRE-only image for the final image (much smaller than JDK)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
