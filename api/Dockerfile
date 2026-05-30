# syntax=docker/dockerfile:1.7
# Production image (linux/amd64) for the Rinha de Backend 2026 fraud API.
#
# Layout:
#   /usr/local/lib/libfaiss*.so   — vendored, built once by docker/Dockerfile.faiss-builder
#   /app/api.jar                  — compiled here (stage `build`)
#   /app/resources/data.faiss     — prebuilt by scripts/preprocess_faiss.py
#   /app/resources/labels.bin     — prebuilt by scripts/preprocess_faiss.py
#
# Build:  docker build --platform linux/amd64 -t luccarhaddad/rinha2026-java:latest -f api/Dockerfile .
# Push:   docker push luccarhaddad/rinha2026-java:latest

# ---- stage 1: compile the jar ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY api/pom.xml /src/api/pom.xml
# Pre-resolve deps for cacheable layer
RUN cd api && mvn -B -q -DskipTests dependency:go-offline || true
COPY api/src /src/api/src
RUN cd api && mvn -B -q -DskipTests clean package

# ---- stage 2: runtime ----
FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libopenblas0 libgomp1 libgfortran5 && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /app/resources /usr/local/lib

# FAISS native libs (linux/amd64; built once via docker/Dockerfile.faiss-builder)
COPY vendor/linux-amd64/libfaiss.so       /usr/local/lib/
COPY vendor/linux-amd64/libfaiss_avx2.so  /usr/local/lib/
COPY vendor/linux-amd64/libfaiss_c.so     /usr/local/lib/
COPY vendor/linux-amd64/FAISS-LICENSE     /usr/local/lib/
RUN ldconfig

# App jar + prebuilt FAISS index + labels
COPY --from=build /src/api/target/api.jar /app/api.jar
COPY api/src/main/resources/data.faiss    /app/resources/data.faiss
COPY api/src/main/resources/labels.bin    /app/resources/labels.bin

ENV LD_LIBRARY_PATH=/usr/local/lib \
    FAISS_C_LIB_PATH=/usr/local/lib/libfaiss_c.so \
    FAISS_INDEX=/app/resources/data.faiss \
    LABELS_BIN=/app/resources/labels.bin \
    KERNEL=faiss \
    FAISS_NPROBE=8 \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", \
    "--add-modules=jdk.incubator.vector", \
    "--enable-native-access=ALL-UNNAMED", \
    "-XX:+UseG1GC", \
    "-Xmx32m", \
    "-XX:MaxMetaspaceSize=40m", \
    "-XX:ReservedCodeCacheSize=32m", \
    "-XX:ActiveProcessorCount=1", \
    "-XX:+UseTransparentHugePages", \
    "-XX:-UsePerfData", \
    "-jar", "/app/api.jar"]
