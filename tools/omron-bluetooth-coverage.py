#!/usr/bin/env python3
"""Compare a locally supplied Omron BP catalog with synthetic fixture coverage.

Writes a research report under the ignored build directory. Catalog presence and
fixture coverage never establish physical compatibility or authorize device writes.
"""
import argparse
import json
from pathlib import Path
import re
import zipfile

ADVERTISEMENT = re.compile(r"^advertise\s+.*?\bname=BLEsmart_([0-9a-f]{4})([0-9a-f]{4})(?:[0-9a-f]*)(?=\s|$)", re.MULTILINE | re.IGNORECASE)
STRICT = re.compile(r"^expect omron matches\s*$", re.MULTILINE)


def coverage(models, fixtures):
    """One row per enabled BP advertisement ID, with exact fixture matches."""
    rows = {}
    for model in models:
        if model.get("category") != 0 or not model.get("enabled", True):
            continue
        for key in ("deviceGroupIncludedGroupIDKey", "secondaryDeviceGroupIncludedGroupIDKey"):
            value = model.get(key)
            if value in (None, ""):
                continue
            group = int(value)
            # The app uses zero as the absent secondary-ID sentinel, not another device.
            if key == "secondaryDeviceGroupIncludedGroupIDKey" and group == 0:
                continue
            identifier = model["identifier"]
            if group in rows:
                if rows[group]["identifier"] != identifier:
                    raise ValueError(f"Conflicting BP catalog entries for group {group}")
                continue
            rows[group] = dict(group=group, identifier=identifier, name=model.get("name", identifier), fixtures=[], strict=[])
    for name, text in sorted(fixtures.items()):
        # Match Fixture.parse(): comments and indentation do not change a directive.
        text = "\n".join(line.split("#", 1)[0].strip() for line in text.splitlines())
        advertised = ADVERTISEMENT.search(text)
        if not advertised or int(advertised[1], 16) != 0:
            continue
        row = rows.get(int(advertised[2], 16))
        if row is not None:
            row["fixtures"].append(name)
            if STRICT.search(text):
                row["strict"].append(name)
    return sorted(rows.values(), key=lambda row: row["group"])


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--omron-dir", type=Path, default=root / "omron-sandbox/build/omron")
    parser.add_argument("--output", type=Path, default=root / "omron-sandbox/build/omron-sandbox/bluetooth-coverage.md")
    args = parser.parse_args()
    try:
        models = json.loads((args.omron_dir / "device_models.json").read_text())
        version = (args.omron_dir / "version.txt").read_text().strip()
        fixture_root = root / "protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures"
        names = [line.split("#", 1)[0].strip() for line in (fixture_root / "index").read_text().splitlines()]
        fixtures = {name: (fixture_root / (name + ".fixture")).read_text() for name in names if name}
        rows = coverage(models, fixtures)
        facts = {}
        with zipfile.ZipFile(args.omron_dir / "device_category.zip") as archive:
            for name in archive.namelist():
                if name.startswith("__MACOSX") or "/0x0000/" not in name or not name.endswith("/DeviceConfig.sys"):
                    continue
                text = archive.read(name).decode("utf-8-sig", errors="replace")
                facts[name.split("/")[-2]] = dict(re.findall(r"^(model_id|connect_type)\s*=\s*([^\r\n;]+)", text, re.MULTILINE))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Cannot audit the local package: {error}\nSee docs/omron-sandbox.md for package setup.\n")
    covered = sum(bool(row["fixtures"]) for row in rows)
    strict = sum(bool(row["strict"]) for row in rows)
    lines = ["# Local Bluetooth blood pressure fixture coverage", "", f"OMRON Connect {version}.", "",
             f"{len(rows)} enabled BP advertisement IDs; {covered} with a fixture; {strict} with an opt-in reference regression.", "",
             "This inventories scripts, not executed results or hardware support. Consult the sandbox transcripts for outcomes.",
             "A matching script must check decoded readings as well as the complete transfer. Pair-only coverage does not prove history import.", "",
             "| ID | Catalog identifier | Config model | Config transport | Fixtures | Strict reference scripts |", "| --- | --- | --- | --- | --- | --- |"]
    for row in rows:
        config = facts.get(row["identifier"], {})
        cells = [f"0x{row['group']:04X}", row["identifier"], config.get("model_id", "missing"), config.get("connect_type", "missing"), ", ".join(row["fixtures"]) or "pending", ", ".join(row["strict"]) or "none"]
        lines.append("| " + " | ".join(str(cell).replace("|", "\\|") for cell in cells) + " |")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n")
    print(f"Bluetooth BP catalog: {len(rows)} IDs, {covered} with fixtures, {strict} with strict reference scripts")
    print(args.output)


if __name__ == "__main__":
    main()
