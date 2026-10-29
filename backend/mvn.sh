#!/usr/bin/env sh
# Runs Maven inside a container so no local JDK is needed: ./mvn.sh verify
# The Docker socket is mounted so Testcontainers can start Postgres/Redis as sibling containers,
# and TESTCONTAINERS_HOST_OVERRIDE points it at the host where those containers publish ports.
set -e
cd "$(dirname "$0")"
HOST_DIR=$(pwd -W 2>/dev/null || pwd)   # Git Bash on Windows needs C:/... paths for -v
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$HOST_DIR":/src -w /src \
  -v orbitlink-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  ${MVN_DOCKER_ARGS:-} \
  maven:3.9-eclipse-temurin-21 mvn -B -ntp "$@"
