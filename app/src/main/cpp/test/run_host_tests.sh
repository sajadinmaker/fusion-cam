#!/usr/bin/env bash
# Host-side test for the Phase 2 fusion core: builds FusionNativeHostTest.cpp
# (which #includes the real FusionNative.cpp) with a host JDK's jni.h and
# asserts alignment / denoise / demosaic behavior on synthetic Bayer bursts.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/.."
JDK="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

g++ -std=c++17 -O1 -g -fsanitize=address,undefined \
    -I"$CPP" -I"$JDK/include" -I"$JDK/include/linux" \
    "$HERE/FusionNativeHostTest.cpp" -o /tmp/fusion_host_test
/tmp/fusion_host_test
