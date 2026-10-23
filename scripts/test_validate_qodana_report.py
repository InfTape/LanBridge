"""Regression cases for Qodana reporting success despite unresolved SDK types."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "validate_qodana_report", Path(__file__).with_name("validate-qodana-report.py")
)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def completed_run(**extra):
    return {"invocations": [{"executionSuccessful": True}], **extra}


class ReportIntegrityTests(unittest.TestCase):
    def validate(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "qodana.sarif.json"
            path.write_text(json.dumps(report), encoding="utf-8")
            return validator.validate_report(path)

    def test_ordinary_findings_do_not_fail_analysis_integrity(self):
        run = completed_run(results=[{"level": "error", "ruleId": "ApplicationBug"}])
        self.assertEqual([], self.validate({"runs": [run]}))

    def test_successful_execution_does_not_hide_sanity_failures(self):
        run = completed_run(properties={"qodana.sanity.results": [
            {"message": {"text": "Unresolved reference java.net.Socket"}}
        ]})
        errors = self.validate({"runs": [run]})
        self.assertTrue(any("sanity-check problems" in error for error in errors))
        self.assertTrue(any("java.net.Socket" in error for error in errors))

    def test_empty_sanity_results_and_zero_findings_are_valid(self):
        run = completed_run(results=[], properties={"qodana.sanity.results": []})
        self.assertEqual([], self.validate({"runs": [run]}))

    def test_failure_in_any_run_is_rejected(self):
        self.assertTrue(self.validate({"runs": [completed_run(), {
            "invocations": [{"executionSuccessful": False}]
        }]}))

    def test_absent_execution_status_is_rejected(self):
        self.assertTrue(self.validate({"runs": [{"results": []}]}))

    def test_empty_and_malformed_reports_are_rejected(self):
        for report in ({}, {"runs": []}, {"runs": [None]}, [], None,
                       {"runs": [completed_run(properties={"qodana.sanity.results": None})]}):
            with self.subTest(report=report):
                self.assertTrue(self.validate(report))

    def test_missing_or_truncated_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "qodana.sarif.json"
            self.assertTrue(validator.validate_report(path))
            path.write_text('{"runs":', encoding="utf-8")
            self.assertTrue(validator.validate_report(path))


if __name__ == "__main__":
    unittest.main()
