FROM gradle:7.4-jdk17 as builder
WORKDIR /build
COPY app/src app/src
COPY app/build.gradle app/build.gradle
COPY settings.gradle .
COPY gradlew .
COPY gradle/ gradle/

RUN ls -al
RUN gradle --no-daemon --console plain --no-scan --no-watch-fs assemble

FROM openjdk:17-alpine as release
WORKDIR /app
COPY --from=builder /build/app/build/distributions/app.tar .
RUN tar xzf app.tar .
RUN rm app.tar
ENTRYPOINT ["/app/bin/recsys-proxy-cache"]
