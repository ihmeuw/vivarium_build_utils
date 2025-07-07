====================
Vivarium Build Utils
====================

Vivarium Build Utils contains shared build utilities for Simulation Science projects.

You can install ``vivarium_build_utils`` from PyPI with pip::

  $ pip install vivarium_build_utils

or build it from source with::

  $ git clone https://github.com/ihmeuw/vivarium.git
  $ cd vivarium
  $ conda create -n ENVIRONMENT_NAME python=3.11
  $ pip install -e .[dev]

## Overview

This repository provides:

- **`vars/`**: Jenkins shared library functions for continuous integration pipelines
- **`resources/`**: Shared Makefiles and build scripts for consistent build processes
