#!/usr/bin/env python3
"""Run from the repo root after `./gradlew test` (Docker must be running for the Testcontainers tests).
Prints line coverage per module and overall from the JaCoCo XML reports."""
import glob
import xml.etree.ElementTree as ET

total_cov = total_miss = 0
for path in sorted(glob.glob("**/build/reports/jacoco/test/jacocoTestReport.xml", recursive=True)):
    root = ET.parse(path).getroot()
    line = next((c for c in root.findall("counter") if c.get("type") == "LINE"), None)
    if line is None:
        continue
    cov, miss = int(line.get("covered")), int(line.get("missed"))
    total_cov += cov
    total_miss += miss
    module = path.split("/build/")[0]
    print(f"{module:35s} {100 * cov / max(cov + miss, 1):5.1f}%  ({cov}/{cov + miss} lines)")

print("-" * 60)
print(f"{'OVERALL':35s} {100 * total_cov / max(total_cov + total_miss, 1):5.1f}%  ({total_cov}/{total_cov + total_miss} lines)")
