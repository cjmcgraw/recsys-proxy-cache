#! /usr/bin/env bash
set -eu
rand=$(head -c16 </dev/urandom|xxd -p -u)
json=$(cat << JSON_DATA
{
    "modelName": "recsys",
    "context": {
        "fields": {
            "country": {
                "values": ["US", "CA", "MX"]
            },
            "language": {
                "values": ["english", "french", "spanish"]
            },
            "site": {
                "values": ["some-test-site.com", "some-other-site.ca", "other-site.mx"]
            },
            "session": {
                "values": ["$rand"]
            }
        }
    },
    "items": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]
}
JSON_DATA
)

echo "using data:"
echo "$json"

echo "$json" | docker run \
    "--network" "recsys-proxy-cache_recsys-static-network" \
    -i \
    -v "$(pwd)/recsys-proxy-cache/java/app/src/main/proto/:/proto" \
    fullstorydev/grpcurl \
        "-plaintext" \
        "-d" "@" \
        "-proto" "/proto/recsys.proto" \
        "-import-path" "/proto" \
        "recsys-proxy-cache:50051" \
        "recsys.RecsysProxyCache.GetScores"

