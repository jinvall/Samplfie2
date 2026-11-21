#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ ! -x "$ROOT_DIR/desktop/build/install/samplfie2-desktop/bin/samplfie2-desktop" ]; then
  echo "Building Samplfie2 Linux Desktop Companion..."
  gradle :desktop:installDist
fi

exec "$ROOT_DIR/desktop/build/install/samplfie2-desktop/bin/samplfie2-desktop" "$@"
