# Runtime image for harness builds (Dockerization plan, sections 3.2-3.5).
#
#   docker build -f docker/runtime.Dockerfile -t lltas-runtime:0.1 docker
#
# Every JDK a build may need, at fixed paths, so toolchains never come from
# the host or from a download. The base images are pinned by multi-arch
# digest (arm64 and amd64); the tag in each comment is what the digest was
# taken from. The pipeline reads the labels below at startup instead of
# hard-coding this layout, and records the image ID with every run.
#
# Phase 1 runs only Gradle here. OpenCode, ripgrep, and the Checker Framework
# at /opt/checker-framework arrive with agent containers in Phase 2; until
# then build containers mount the vendored Checker Framework read-only at its
# host path, so both backends use the same jars.

# eclipse-temurin:8-jdk-noble   (jdk8u512-b09)
FROM eclipse-temurin@sha256:b8a2bc97792c866eb8c96981fbc18eb4d141df8a27cfc28f825b2372fcea6359 AS jdk8
# eclipse-temurin:11-jdk-noble  (jdk-11.0.33+10)
FROM eclipse-temurin@sha256:72700e0ae26cbf3d60b6e4e34a7e14cc8fe006d1fc4a7019afc0623a5264a32d AS jdk11
# eclipse-temurin:17-jdk-noble  (jdk-17.0.21+9)
FROM eclipse-temurin@sha256:bdd7eeee8f2259194d39a9525f09abfddcb97a7bdc6fcd97de6f83652e80f772 AS jdk17
# eclipse-temurin:25-jdk-noble  (jdk-25.0.5.1+2)
FROM eclipse-temurin@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS jdk25

# eclipse-temurin:21-jdk-noble  (jdk-21.0.13+9): the default Gradle runtime.
FROM eclipse-temurin@sha256:a09e64f4976df9c4f2029c6abe64fc26f069a342ec9a7f147dce7b1933ebda16

# Tools build scripts shell out to, as they would on a developer machine:
# git during configuration (JSqlParser's `git describe`), and curl plus the
# common archive formats for scripts that fetch and unpack a toolchain
# (Agrona's native library bootstraps Zig from a .tar.xz).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git curl ca-certificates xz-utils bzip2 zstd unzip zip \
 && rm -rf /var/lib/apt/lists/*

COPY --from=jdk8  /opt/java/openjdk /opt/java/8
COPY --from=jdk11 /opt/java/openjdk /opt/java/11
COPY --from=jdk17 /opt/java/openjdk /opt/java/17
COPY --from=jdk25 /opt/java/openjdk /opt/java/25
RUN mv /opt/java/openjdk /opt/java/21

# Worktrees and the Git mirror are bind-mounted and may be owned by another
# uid; git refuses such repositories without this.
RUN git config --system safe.directory '*'

# Non-root user. The base image's `ubuntu` user holds uid 1000. The Gradle
# home is created here, owned by the user, so the named volume mounted over
# it starts with that ownership.
RUN userdel --remove ubuntu 2>/dev/null || true \
 && useradd --create-home --uid 1000 --home-dir /home/lltas --shell /bin/bash lltas \
 && mkdir -p /gradle-home \
 && chown lltas:lltas /gradle-home

ENV JAVA_HOME=/opt/java/21 \
    PATH=/opt/java/21/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    GRADLE_USER_HOME=/gradle-home \
    HOME=/home/lltas \
    LANG=C.UTF-8

LABEL lltas.jdks="8,11,17,21,25" \
      lltas.jdk_root="/opt/java" \
      lltas.gradle_home="/gradle-home" \
      lltas.home="/home/lltas"

USER lltas
WORKDIR /home/lltas
