#!/usr/bin/env bash
# Run the Python tests of tools/ with coverage; writes target/python-coverage.xml (Cobertura) for SonarQube.
# Uses a local coverage.py when installed, otherwise a python container.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
mkdir -p target

run_coverage='python3 -m coverage run --rcfile=tools/.coveragerc -m unittest discover -s tools/tests \
  && python3 -m coverage xml --rcfile=tools/.coveragerc \
  && python3 -m coverage report --rcfile=tools/.coveragerc'

if python3 -c 'import coverage' 2>/dev/null; then
  sh -c "$run_coverage"
else
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e PYTHONPATH=/tmp/pylib \
    -v "$repo_root:/w" -w /w python:3.13-slim \
    sh -c "pip install -q --disable-pip-version-check --target /tmp/pylib coverage && $run_coverage"
fi
