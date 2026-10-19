#!/usr/bin/env python3
"""Synthetic checks for the local Bluetooth BP catalog coverage report."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("coverage_report", Path(__file__).with_name("omron-bluetooth-coverage.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class CoverageTest(unittest.TestCase):
    def model(self, identifier="HEM-0000T-X", group="42", **extra):
        return dict(category=0, enabled=True, identifier=identifier, name="Synthetic BP", deviceGroupIncludedGroupIDKey=group, secondaryDeviceGroupIncludedGroupIDKey="", **extra)

    def test_every_exact_advertised_id_keeps_its_own_coverage(self):
        models = [self.model(), self.model("HEM-0000T-Y", "43")]
        fixtures = {"test/x": "advertise name=BLEsmart_0000002A00112233 services=x\nflow connect\nexpect omron matches\nexpect ours rejected\n"}
        rows = report.coverage(models, fixtures)
        self.assertEqual([(42, ["test/x"]), (43, [])], [(r["group"], r["fixtures"]) for r in rows])
        self.assertEqual(["test/x"], rows[0]["strict"])

    def test_disabled_and_non_bp_entries_are_excluded(self):
        disabled = self.model(); disabled["enabled"] = False
        scale = self.model(); scale["category"] = 1
        self.assertEqual([], report.coverage([disabled, scale], {}))

    def test_secondary_ids_and_duplicate_catalog_rows_are_not_lost(self):
        model = self.model(); model["secondaryDeviceGroupIncludedGroupIDKey"] = "44"
        rows = report.coverage([model, dict(model)], {"test/secondary": "advertise name=BLEsmart_0000002C\n"})
        self.assertEqual([42, 44], [r["group"] for r in rows])
        self.assertEqual(["test/secondary"], rows[1]["fixtures"])
        self.assertEqual([], rows[1]["strict"])

    def test_zero_secondary_id_means_no_alternative_device(self):
        first = self.model(); first["secondaryDeviceGroupIncludedGroupIDKey"] = "0"
        second = self.model("HEM-0000T-Y", "43"); second["secondaryDeviceGroupIncludedGroupIDKey"] = "0"
        self.assertEqual([42, 43], [r["group"] for r in report.coverage([first, second], {})])

    def test_conflicting_catalog_identity_fails_instead_of_hiding_a_device(self):
        with self.assertRaisesRegex(ValueError, "42"):
            report.coverage([self.model(), self.model("HEM-0000T-Y")], {})

    def test_another_category_or_partial_advertisement_never_covers_bp(self):
        rows = report.coverage([self.model()], {"test/scale": "advertise name=BLEsmart_0001002A\n", "test/truncated": "advertise name=BLEsmart_0000002\n"})
        self.assertEqual([], rows[0]["fixtures"])

    def test_fixture_whitespace_and_comments_follow_the_shared_parser(self):
        fixture = "  advertise name=BLEsmart_0000002A # synthetic\n  expect omron matches # reference\n"
        rows = report.coverage([self.model()], {"test/commented": fixture})
        self.assertEqual(["test/commented"], rows[0]["fixtures"])
        self.assertEqual(["test/commented"], rows[0]["strict"])


if __name__ == "__main__":
    unittest.main()
