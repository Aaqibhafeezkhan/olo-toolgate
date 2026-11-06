# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Model-check the trigger delivery specification (ADR 058, gate G-STATE item 1).

Runs TLC on docs/specs/trigger_delivery.tla. Every configuration in EXHAUSTIVE is checked breadth-first over its
whole state space at 2 items; crash and failover are split into separate configurations because together they
exceed an exhaustive run. Every configuration in SIMULATED combines all faults at the plan 6.6.1 bound of 3 items and
is explored by TLC random simulation for a fixed time budget: evidence, not proof. TLC is not vendored; point
TLA2TOOLS_JAR at a tla2tools.jar from the official TLA+ release. Exits non-zero on any violation, parse error, a
missing configuration, or an exhaustive run that does not complete.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPECS = ROOT / 'docs/specs'
STATES = ROOT / '.dev/tlc'
MODULE = 'trigger_delivery'
EXHAUSTIVE = ('core_halt', 'core_skip_failover', 'core_skip_crash', 'lifecycle_failover', 'lifecycle_crash',
              'liveness_failover', 'liveness_crash')
SIMULATED = ('core_skip_full', 'lifecycle_full')
SIMULATION_SEED = '20261107'
PASS = 'Model checking completed. No error has been found.'


def run(jar, name, minutes):
    """Check one configuration; minutes > 0 runs a random simulation for that long instead of exhaustive search."""
    config = SPECS / f'{MODULE}_{name}.cfg'
    if not config.is_file():
        raise SystemExit(f'Missing model configuration: {config.relative_to(ROOT).as_posix()}')
    # TLC state files can reach gigabytes: keep them on the checkout's drive (git-ignored .dev/), not the temp drive.
    STATES.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='tlc-', dir=STATES) as states:
        mode = ['-simulate', '-depth', '100', '-seed', SIMULATION_SEED] if minutes else ['-cleanup']
        command = ['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', *mode,
                   '-metadir', states, '-config', str(config), str(SPECS / f'{MODULE}.tla')]
        print('TLC:', config.name, f'simulation for {minutes} min' if minutes else 'exhaustive', flush=True)
        log = Path(states) / 'tlc.log'
        with log.open('w', encoding='utf-8') as sink:
            process = subprocess.Popen(command, cwd=SPECS, stdout=sink, stderr=subprocess.STDOUT)
            try:
                code = process.wait(timeout=minutes * 60 if minutes else None)
                budget_spent = False
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                code, budget_spent = 0, True
        output = log.read_text(encoding='utf-8', errors='replace')
    print('\n'.join(line for line in output.splitlines()
                    if 'states generated' in line or 'Error' in line or 'violated' in line
                    or 'depth of the complete' in line or PASS in line))
    if minutes:
        checked = re.findall(r'Progress: (\d+) states checked', output)
        print(f'Simulation: {int(checked[-1]) if checked else 0:,} states checked, no violation'
              if budget_spent and 'Error:' not in output else '')
    failed = code != 0 or 'Error:' in output or (not minutes and PASS not in output)
    if failed:
        print(output[-8000:])
        raise SystemExit(f'TLC failed for {config.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--simulation-minutes', type=int, default=10,
                        help='time budget per simulated configuration (0 skips simulation)')
    parser.add_argument('--only', nargs='+', choices=EXHAUSTIVE + SIMULATED, help='check only these configurations')
    args = parser.parse_args()
    jar = os.environ.get('TLA2TOOLS_JAR')
    if not jar or not Path(jar).is_file():
        raise SystemExit('Set TLA2TOOLS_JAR to tla2tools.jar from the official TLA+ release')
    for name in EXHAUSTIVE:
        if not args.only or name in args.only:
            run(jar, name, 0)
    if args.simulation_minutes:
        for name in SIMULATED:
            if not args.only or name in args.only:
                run(jar, name, args.simulation_minutes)
    print('Trigger delivery model: all selected configurations passed')


if __name__ == '__main__':
    sys.exit(main())
