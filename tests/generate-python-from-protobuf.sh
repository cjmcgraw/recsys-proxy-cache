#!/usr/bin/env bash
mkdir -p src/proto
python -m grpc_tools.protoc \
    -I . \
    --python_betterproto_out=src/proto \
    recsys.proto
