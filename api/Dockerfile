# syntax=docker/dockerfile:1.7
# Production image (linux/amd64). v8 — custom jlink JRE on slim Ubuntu base.
# Build: docker build --platform linux/amd64 -t luccarhaddad/rinha2026-java:faiss-v8 -f api/Dockerfile .

# ---- stage 1: compile the jar ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY api/pom.xml /src/api/pom.xml
RUN cd api && mvn -B -q -DskipTests dependency:go-offline || true
COPY api/src /src/api/src
RUN cd api && mvn -B -q -DskipTests clean package

# ---- stage 2: custom minimal JRE via jlink ----
# Keeps only the modules our stack actually loads at runtime. Saves ~50 MB
# vs the full eclipse-temurin:25-jre image (which bundles every standard module).
FROM eclipse-temurin:25-jdk AS jre-build
RUN jlink \
    --add-modules java.base,java.logging,java.management,java.naming,java.security.jgss,jdk.unsupported,jdk.crypto.ec,jdk.incubator.vector,jdk.zipfs,jdk.jfr,jdk.management \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /jre
RUN du -sh /jre

# ---- stage 3: runtime ----
# Ubuntu 24.04 base (same as libfaiss build env — guarantees glibc 2.39 ABI match).
# Without the full JRE image, base size drops from ~225 MB to ~80 MB.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
        libopenblas0 libgomp1 libgfortran5 ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /app/resources /usr/local/lib /opt/java

# Custom JRE built via jlink in stage 2
COPY --from=jre-build /jre /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# FAISS native libs (built once via docker/Dockerfile.faiss-builder)
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
    VERTX_EVENT_LOOPS=2
# SOCK_PATH is set per-instance by docker-compose (api-1.sock, api-2.sock).
# Vert.x listens on UDS when SOCK_PATH is set, falls back to TCP $PORT otherwise.
# G1 stays: ZGC was tested (v7) and didn't fit our 160 MiB budget — its native
# overhead (~25 MiB) is >80% of a 32 MiB heap and triggered 46s startup thrashing.
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
