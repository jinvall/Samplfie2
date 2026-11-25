#!/usr/bin/env python3
"""Entrypoint dispatcher for ./integrity and ./apidacs CLI commands."""

import os
import sys
from pathlib import Path

TOOLKIT_ROOT = Path(__file__).resolve().parent
if str(TOOLKIT_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_ROOT))

from apidacs.cli import main  # noqa: E402

if __name__ == "__main__":
    prog = os.path.basename(sys.argv[0]) or "integrity"
    sys.exit(main(sys.argv[1:], prog_name=prog))
