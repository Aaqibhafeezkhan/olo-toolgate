#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run the LangChain HotFolder listener or its live smoke test in the debug lab."""
import argparse
import os
from pathlib import Path
import sys


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=('listen', 'verify'), default='listen')
    command = parser.parse_args().command
    bringup = Path(__file__).resolve().parents[1] / 'bringup.py'
    os.execv(sys.executable, [sys.executable, str(bringup), command])
