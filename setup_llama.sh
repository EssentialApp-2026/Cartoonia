#!/usr/bin/env sh
# Scarica llama.cpp alla versione verificata con questo progetto.
set -e
TAG=b12282
cd "$(dirname "$0")"
if [ -f third_party/llama.cpp/CMakeLists.txt ]; then
  echo "llama.cpp già presente in third_party/llama.cpp"
  exit 0
fi
mkdir -p third_party
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
echo "Fatto: llama.cpp $TAG scaricato."
