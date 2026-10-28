# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Write baseline coverage and scanner status to the Actions summary."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summary(root):
    lines = ["## Java coverage and Sonar", "", "Reporting only; no coverage threshold or quality gate is enforced.", "",
             f"Java tests/reports: {os.getenv('COVERAGE_OUTCOME', 'unknown')}",
             f"Sonar analysis: {os.getenv('SONAR_OUTCOME', 'unknown')}", "",
             "| Module | Line coverage | Branch coverage |", "| --- | ---: | ---: |"]
    totals = {kind: [0, 0] for kind in ('LINE', 'BRANCH')}
    reports = sorted(root.glob('**/build/reports/jacoco/test/jacocoTestReport.xml'))
    for report in reports:
        counters = {c.attrib['type']: c.attrib for c in ET.parse(report).getroot().findall('counter')}
        values = []
        for kind in totals:
            counter = counters.get(kind, {})
            covered, missed = int(counter.get('covered', 0)), int(counter.get('missed', 0))
            totals[kind][0] += covered
            totals[kind][1] += missed
            values.append(f'{100 * covered / (covered + missed):.2f}%' if covered + missed else 'N/A')
        module = report.relative_to(root).as_posix().split('/build/')[0]
        lines.append(f'| {module} | {values[0]} | {values[1]} |')
    if not reports:
        lines += ['', 'No coverage XML reports were generated. See the Java test step logs.']
    else:
        values = [f'{100*c/(c+m):.2f}%' if c+m else 'N/A' for c, m in totals.values()]
        lines.append(f'| **Total** | **{values[0]}** | **{values[1]}** |')
    task = root / 'build/sonar/report-task.txt'
    if task.exists():
        properties = dict(line.split('=', 1) for line in task.read_text().splitlines() if '=' in line)
        url = properties.get('dashboardUrl', '')
        if url.startswith(('https://', 'http://')):
            lines += ['', f'[View Sonar results]({url})', '', 'Server processing may still be in progress.']
    lines += ['', 'If Sonar was skipped, configure SONAR_TOKEN and the SONAR_HOST_URL / SONAR_PROJECT_KEY repository variables. Fork PRs do not receive secrets.', '', 'Download the java-coverage-and-sonar artifact for HTML/XML reports.']
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
        output.write(summary(Path.cwd()))
