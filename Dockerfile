FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY lib/ lib/
COPY *.java ./
RUN mkdir -p out && javac -cp "lib/*" -d out *.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out/ out/
COPY lib/ lib/
COPY frontend/ frontend/
CMD ["java", "-cp", "out:lib/*", "Login"]
