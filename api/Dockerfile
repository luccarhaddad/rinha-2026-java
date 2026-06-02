# syntax=docker/dockerfile:1.7
# Production image (linux/amd64).
# Build: docker build --platform linux/amd64 -t luccarhaddad/rinha2026-java:faiss-v6 -f api/Dockerfile .

# ---- stage 1: compile the jar ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY api/pom.xml /src/api/pom.xml
RUN cd api && mvn -B -q -DskipTests dependency:go-offline || true
COPY api/src /src/api/src
RUN cd api && mvn -B -q -DskipTests clean package

# ---- stage 2: runtime ----
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
        libopenblas0 libgomp1 libgfortran5 && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /app/resources /usr/local/lib

COPY vendor/linux-amd64/libfaiss.so       /usr/local/lib/
COPY vendor/linux-amd64/libfaiss_avx2.so  /usr/local/lib/
COPY vendor/linux-amd64/libfaiss_c.so     /usr/local/lib/
COPY vendor/linux-amd64/FAISS-LICENSE     /usr/local/lib/
RUN ldconfig

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
    VERTX_EVENT_LOOPS=2
# SOCK_PATH is set per-instance by docker-compose (api-1.sock, api-2.sock).
# Vert.x listens on UDS when SOCK_PATH is set, falls back to TCP $PORT otherwise.
# (AOT cache was tried and dropped: +25 MiB resident for our tiny code surface
#  with no measurable JIT/startup win — net cost on this 160 MiB budget.)
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
