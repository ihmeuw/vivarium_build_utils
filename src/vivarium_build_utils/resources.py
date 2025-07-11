"""Utilities for finding vivarium_build_utils resources."""

import os
from pathlib import Path


def get_resources_path():
    """Get the path to the vivarium_build_utils resources directory.

    Returns:
        Path to the resources directory.
    """
    this_file = Path(__file__)

    # Try package-relative location first (for installed package)
    package_resources = this_file.parent / "resources"
    if package_resources.exists():
        return str(package_resources)

    # Try repository root (for development and Jenkins)
    repo_root_resources = this_file.parent.parent.parent / "resources"
    if repo_root_resources.exists():
        return str(repo_root_resources)

    # Fallback: assume it's at package location
    return str(package_resources)


def get_makefiles_path():
    """Get the path to the makefiles directory."""
    return os.path.join(get_resources_path(), "makefiles")
