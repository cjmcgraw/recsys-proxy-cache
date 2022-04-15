FROM python:3.10
RUN apt update -yqq && \
    apt install -yqq \
        telnet \
        iputils-ping \
        protobuf-compiler \
        libc6-dev

WORKDIR /app

COPY requirements.txt .
RUN \
    pip install --cache-dir /pip_cache -r requirements.txt

COPY pyproject.toml .
COPY recsys.proto .
COPY src/ ./src
COPY generate-python-from-protobuf.sh .
RUN ./generate-python-from-protobuf.sh
COPY docker-entrypoint.sh .
ENTRYPOINT [ "./docker-entrypoint.sh" ]
