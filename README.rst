# Vivarium Build Utils

Shared build utilities and Jenkins pipeline library for Simulation Science projects.

## Overview

This repository provides:

- **`vars/`**: Jenkins shared library functions for continuous integration pipelines
- **`resources/`**: Shared Makefiles and build scripts for consistent build processes

## Usage

### Jenkins Shared Library

Configure Jenkins to use this repository as a shared library, then reference specific versions in your Jenkinsfile:

```groovy
@Library('vivarium_build_utils@v1.0.0') _
reusable_pipeline(scheduled_branches: ["main"], upstream_repos: ["layered_config_tree"])
```

### Makefiles

Reference the shared makefiles from your project's Makefile:

```makefile
# Check if we're running in Jenkins
ifdef JENKINS_URL
	# Files are already in workspace from shared library
	MAKE_INCLUDES := .
else
	# For local dev, search in parent directory  
	MAKE_INCLUDES := ../vivarium_build_utils/resources/makefiles
endif

PACKAGE_NAME = your_package_name

# Include makefiles from vivarium_build_utils
include $(MAKE_INCLUDES)/base.mk
include $(MAKE_INCLUDES)/test.mk
```

### Version Pinning in Python Projects

To pin `vivarium_build_utils` to a specific version in your `setup.py`:

```python
# In your setup.py dependencies
install_requires = [
    "vivarium_build_utils<=2.0.0",
    # ... other dependencies
]
```

Or install directly:
```bash
pip install "vivarium_build_utils@git+https://github.com/ihmeuw/vivarium_build_utils.git@v1.0.0"
```

## Versioning

This repository uses semantic versioning with Git tags:

**To create a new release:**
```bash
git tag v1.0.0
git push origin v1.0.0
```

**To pin to a specific version:**
- Jenkins: `@Library('vivarium_build_utils@v1.0.0')`
- Python setup.py: `"vivarium_build_utils<=2.0.0"`
- Direct install: `pip install vivarium_build_utils@git+...@v1.0.0`

## Repository Structure

- `vars/`: Jenkins pipeline shared library functions (used by Jenkins)
- `resources/makefiles/`: Shared Makefile includes  
- `resources/scripts/`: Build and utility scripts
- `setup.py` & `pyproject.toml`: Versioning and package metadata (no actual Python code)
