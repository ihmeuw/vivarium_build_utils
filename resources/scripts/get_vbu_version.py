#!/usr/bin/env python3
"""
Script to determine the vivarium_build_utils version for a project.

This script:
1. Reads python_versions.json to get the maximum supported Python version
2. Runs pip with --dry-run to resolve dependencies
3. Extracts the vivarium_build_utils version from the output
4. Returns the version with 'v' prefix for git tagging convention

"""

import json
import os
import re
import subprocess
import sys

MINICONDA_DIR = "/svc-simsci/miniconda3"


def _get_max_python_version():
    """Read python_versions.json and return the maximum supported Python version."""
    try:
        with open("python_versions.json", "r") as f:
            versions = json.load(f)
        return max(versions)
    except FileNotFoundError:
        print(
            "ERROR: python_versions.json not found in current directory",
            file=sys.stderr,
        )
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"ERROR: Invalid JSON in python_versions.json: {e}", file=sys.stderr)
        sys.exit(1)


def _run_pip_dry_run(python_version):
    """Run pip install --dry-run and return the output.

    Notes
    -----
    This function uses previously-created python-only environments located
    on our shared filesystem, e.g. py311, py312, etc.
    """
    # Format python version for conda environment (e.g., "3.11" -> "311")
    env_version = python_version.replace(".", "")

    # Construct the command to activate conda environment and run pip
    cmd = f"""
    source {MINICONDA_DIR}/etc/profile.d/conda.sh
    conda activate py{env_version}
    pip install --dry-run .
    """

    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, check=True
        )
        return result.stdout
    except subprocess.CalledProcessError as e:
        print(f"ERROR: Failed to run pip dry-run: {e}", file=sys.stderr)
        print(f"STDERR: {e.stderr}", file=sys.stderr)
        sys.exit(1)


def _extract_vbu_version(dry_run_output):
    """Extract vivarium_build_utils version from pip dry-run output."""
    # Look for line containing "Would install" and extract version
    for line in dry_run_output.split("\n"):
        if "Would install" in line and "vivarium_build_utils" in line:
            # Use regex to extract version number
            match = re.search(
                r"vivarium_build_utils-([0-9]+\.[0-9]+\.[0-9]+[^\s]*)", line
            )
            if match:
                version = match.group(1)
                # Add 'v' prefix for git tagging convention
                return f"v{version}"

    print(
        "ERROR: Could not find vivarium_build_utils version in pip dry-run output",
        file=sys.stderr,
    )
    print("Dry-run output:", file=sys.stderr)
    print(dry_run_output, file=sys.stderr)
    sys.exit(1)


def main():
    """Main function to orchestrate version resolution."""
    # Ensure we're in a directory with python_versions.json
    if not os.path.exists("python_versions.json"):
        print(
            "ERROR: python_versions.json not found in current directory",
            file=sys.stderr,
        )
        sys.exit(1)

    python_version = _get_max_python_version()
    dry_run_output = _run_pip_dry_run(python_version)
    vbu_version = _extract_vbu_version(dry_run_output)

    print(vbu_version)
    return vbu_version


if __name__ == "__main__":
    main()
