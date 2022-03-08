FROM gcc:11-bullseye
RUN apt-get update -yqq && \
    apt-get install -yqq \
    clang libc++-dev apt-transport-https autoconf build-essential libtool pkg-config apt-utils curl gnupg google-perftools git zip unzip && \
    curl -fsSL https://bazel.build/bazel-release.pub.gpg | gpg --dearmor > bazel.gpg && \
    mv bazel.gpg /etc/apt/trusted.gpg.d/ && \
    echo "deb [arch=amd64] https://storage.googleapis.com/bazel-apt stable jdk1.8" > /etc/apt/sources.list.d/bazel.list && \
    apt-get update -yqq && \
    apt-get install -yqq bazel && \
    apt-get full-upgrade
RUN bazel --version
RUN git clone --recursive https://github.com/grpc/grpc.git
