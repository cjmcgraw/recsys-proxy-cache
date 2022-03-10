#! /usr/bin/env bash

function help() {
    echo ""
    echo "$0 <args>"
    echo ""
    echo "  Entrypoint to the tests. The purpose of this script is"
    echo "  to ensure that the state is correct before running any"
    echo "  tests."
    echo ""
    echo "  If you see an error during execution it is most likely because"
    echo "  a container had an issue. Please consider the following"
    echo ""
    echo "  check the docker state for failures:"
    echo "      $ docker compose ps"
    echo ""
    echo "  ensure the containers are up:"
    echo "      $ docker compose up -d"
    echo ""
    echo "  run this script in its expected docker container:"
    echo "      $ docker compose run tests"
    echo ""
}

valid_hosts=(
    recsys-proxy-cache
)

for host in "${valid_hosts[@]}"; do
    
    echo "pinging host ${host} to see if its available..."
    ping -A -w 5 -c 5 "${host}"
    exit_code=$?
    if [[ "${exit_code}" -gt 0 ]]; then
        echo "ping host=${host} failed!"
        help
        exit 1
    fi
    echo "succesfully found host=${host}"
    echo ""
    echo "checking if host connection can be opened..."
    attempts_remaining=30
    success=""
    
    while [[ "${attempts_remaining}" -gt 0 && -z "${success}" ]]; do
        echo -e '\x1dclose\x0d' | telnet "${host}" 50051
        exit_code=$?
        if [[ "${exit_code}" -eq 0 ]]; then
            success="true"
        else
            sleep 1
        fi
    done

    if [[ -z "${success}" ]]; then
        echo "failed to open expected port on host=${host}"
        echo ""
        help
        exit 1
    fi
    echo "succesfully found port open on host=${host}"
    echo ""
done

echo "done!"
cd src/
python -m pytest
