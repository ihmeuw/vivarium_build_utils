====================
Vivarium Build Utils
====================

Vivarium Build Utils contains shared build utilities for Simulation Science projects.

You can install ``vivarium_build_utils`` from PyPI with pip::

  $ pip install vivarium_build_utils

or build it from source with::

  $ git clone https://github.com/ihmeuw/vivarium_build_utils.git
  $ cd vivarium_build_utils
  $ conda create -n ENVIRONMENT_NAME
  $ pip install -e .

Overview
========

This repository provides:

- **`vars/`**: Jenkins shared library functions for continuous integration pipelines
- **`resources/`**: Shared Makefiles and build scripts for consistent build processes

Make Commands and Jenkins Workflow
===================================

This repository contains shared Makefiles with various ``make`` commands that
(1) provide a consistent interface for the various stages of a Jenkins build and
(2) provide helper functions for users to run common tasks locally.

Quick Start
-----------

In any project using ``vivarium_build_utils``:

.. code-block:: bash

  # See Make's standard help
  make --help

  # See our team's curated help
  make help

  # See all available make targets
  make list

  # Format the code using isort and black
  make format

  # Check current code base for common issues
  make check

  # Install upstream dependencies
  make install-upstream-deps

  # Manually deploy a package to Artifactory (useful if Jenkins deploy fails)
  make manual-deploy-artifactory
