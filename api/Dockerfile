# Fallback only; Jib (configured in pom.xml) is the primary build. Targets linux/amd64.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN cd api && mvn -q -DskipTests clean package

FROM gcr.io/distroless/java-base-debian12:nonroot
WORKDIR /app
COPY --from=build /src/api/target/api.jar /app/api.jar
COPY --from=build /src/api/src/main/resources/dataset.i8bin /app/resources/dataset.i8bin
COPY --from=build /src/api/src/main/resources/labels.bin /app/resources/labels.bin
ENV DATASET_BIN=/app/resources/dataset.i8bin
ENV LABELS_BIN=/app/resources/labels.bin
# G1 (not ZGC): at this tiny heap ZGC's footprint (~+45MB) blows the 160MB/instance budget.
# Workload is low-allocation (pooled scratch), so G1's pauses are rare and short.
ENTRYPOINT ["java","--add-modules=jdk.incubator.vector","-Xmx64m","-XX:+UseG1GC","-XX:MaxMetaspaceSize=64m","-XX:-UsePerfData","-jar","/app/api.jar"]
