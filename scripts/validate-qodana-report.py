"""Reject incomplete Qodana analysis, without imposing an application-issue gate."""

import argparse
import json
from pathlib import Path
import sys


def validate_report(path: Path) -> list[str]:
    try:
        report = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        return [f"Cannot read the Qodana SARIF report: {error}"]

    if not isinstance(report, dict):
        return ["Qodana SARIF must be an object."]
    runs = report.get("runs")
    if not isinstance(runs, list) or not runs:
        return ["Qodana SARIF contains no analysis runs."]

    errors = []
    for index, run in enumerate(runs, start=1):
        if not isinstance(run, dict):
            errors.append(f"Run {index} is not an analysis object.")
            continue
        invocations = run.get("invocations")
        if not isinstance(invocations, list) or not invocations:
            errors.append(f"Run {index} has no execution status.")
        else:
            for invocation in invocations:
                if not isinstance(invocation, dict) or invocation.get("executionSuccessful") is not True:
                    errors.append(f"Run {index} did not complete successfully.")

        properties = run.get("properties", {})
        if not isinstance(properties, dict):
            errors.append(f"Run {index} has invalid analysis properties.")
            continue
        sanity = properties.get("qodana.sanity.results", [])
        if not isinstance(sanity, list):
            errors.append(f"Run {index} has invalid sanity-check results.")
        elif sanity:
            errors.append(
                f"Run {index} contains {len(sanity)} sanity-check problems; "
                "check SDK/JDK availability and Gradle import before trusting findings."
            )
            for result in sanity[:20]:
                if isinstance(result, dict):
                    message = result.get("message", {})
                    if isinstance(message, dict) and isinstance(message.get("text"), str):
                        errors.append(f"  {message['text']}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    arguments = parser.parse_args()
    errors = validate_report(arguments.report)
    if errors:
        print("Qodana analysis integrity check failed:", file=sys.stderr)
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Qodana analysis completed without sanity-check problems.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
