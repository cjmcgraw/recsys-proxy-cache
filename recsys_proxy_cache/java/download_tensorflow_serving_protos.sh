#!/usr/bin/env bash
mkdir -p .tmp_proto
cd .tmp_proto
if [[ ! -d "tensorflow" ]]; then
    echo "no tensorflow found. Cloning..."
    git clone https://github.com/tensorflow/tensorflow.git &
else
    echo "tensorflow found. Pulling latest..."
    cd tensorflow
    git pull
    cd ..
fi

if [[ ! -d "serving" ]]; then
    echo "no tensorflow/serving found. Cloning..."
    git clone https://github.com/tensorflow/serving.git &
else
    echo "tensorflow_serving found. Pulling latest..."
    cd serving
    git pull
    cd ..
fi
wait

echo "beginning copy of proto files to java locations"
for project in $(ls); do
    cd "${project}"
    for filepath in $(find . -type f -name '*.proto' | sed 's,^./,,'); do
        filename=$(basename $filepath)
        dir=$(dirname $filepath)
        if [[ -z "${dir}"  || -z "${filename}" ]]; then
            echo "failed: $filepath" >&2 
        fi
        new_location="./../../app/src/main/proto/$dir"
        mkdir -p "${new_location}"
        echo "copying ${filepath}"
        cp "$filepath" "${new_location}"
    done
    cd ..
done
