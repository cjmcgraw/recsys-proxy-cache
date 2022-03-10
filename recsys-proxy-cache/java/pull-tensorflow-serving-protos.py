#! /usr/bin/env python
import subprocess
import argparse
import pathlib
import shutil
import re
import os

working_dir = pathlib.Path(".protos_working_dir").absolute()
java_protos_directory = pathlib.Path("./app/src/main/proto").absolute()
tensorflow_serving_github = "https://github.com/tensorflow/serving.git"
tensorflow_github = "https://github.com/tensorflow/tensorflow.git"


import_regex = re.compile(r'^import "(?P<import_path>[a-zA-Z0-9/_\.]+)";')

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument("--tensorflow-version", default="r2.8")
    p.add_argument("--dry-run", action='store_true')
    args = p.parse_args()
    
    working_dir.mkdir(parents=True, exist_ok=True)
    java_protos_directory.mkdir(parents=True, exist_ok=True)

    serving_dir = working_dir.joinpath("serving")
    if not serving_dir.exists():
        subprocess.run([
            "git", "clone", 
                "--depth", "1", 
                "-b", args.tensorflow_version, 
                "--single-branch",
                tensorflow_serving_github,
                str(serving_dir)
        ])

    tensorflow_dir = working_dir.joinpath("tensorflow")
    if not tensorflow_dir.exists():
        subprocess.run([
            "git", "clone",
                "--depth", "1",
                "-b", args.tensorflow_version,
                tensorflow_github,
                str(tensorflow_dir)
        ])

    known_locations = {
        "tensorflow": tensorflow_dir,
        "tensorflow_serving": serving_dir,
    }

    def get_proto_requirements(proto_file):
        with open(proto_file) as f:
            lines = f.readlines()
        for line in lines:
            if match := import_regex.match(line):
                import_path = pathlib.Path(match.group("import_path"))
                root_path, *rest = import_path.parts
                if root_path != "google":
                    if root_path not in known_locations:
                        print(f"ERROR PROCESSING: {root_path}")
                    yield known_locations[root_path].joinpath(root_path, *rest)



    include_paths = set()
    new_protos = set(serving_dir.rglob("tensorflow_serving/apis/*.proto"))
    valid_protos = set()

    while len(new_protos) > 0:
        current_protos = new_protos
        new_protos = set()
        for filepath in current_protos:
            print(filepath)
            valid_protos.add(filepath)
            for requirements in get_proto_requirements(filepath):
                print(f"  > {requirements}")
                if requirements not in valid_protos:
                    new_protos.add(requirements)

    print(f"found {len(valid_protos)} valid protos")
    for src_path in valid_protos:
        dest_path = pathlib.Path(
            str(src_path)
            .replace(str(tensorflow_dir), str(java_protos_directory))
            .replace(str(serving_dir), str(java_protos_directory))
        )
        assert src_path.exists()
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        if not args.dry_run:
            print("copying proto file:")
        else:
            print("would copy proto file:")
        print(f"  src:  {src_path}")
        print(f"  dest: {dest_path}")
        print("")
        if not args.dry_run:
            shutil.copyfile(src_path, dest_path)


