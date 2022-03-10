FROM gradle:7.4-jdk17 as builder
WORKDIR /build
COPY app/src app/src
COPY app/build.gradle app/build.gradle
COPY settings.gradle .
COPY gradlew .
COPY gradle/ gradle/

RUN ls -al
RUN gradle --no-daemon --console plain --no-scan --no-watch-fs assemble

FROM shipilev/openjdk:17 as release
RUN apt-get -yqq update
RUN apt-get install -yqq tar tree libgrpc-dev google-perftools
RUN find / -type f -name *tcmalloc*
ENV LD_PRELOAD /usr/lib/x86_64-linux-gnu/libtcmalloc_minimal.so.4.5.6
WORKDIR /app
COPY --from=builder /build/app/build/distributions/app.tar .
RUN tar -xf app.tar && rm app.tar && mv app/* . && rm -rf app && tree /app
ENTRYPOINT ["/app/bin/recsys-proxy-cache"]
