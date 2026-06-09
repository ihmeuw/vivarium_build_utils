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

Note: for help with the Make targets available to any environment with this repository
installed, run `make help` in the terminal.

Monorepo support
================

``vivarium_build_utils`` supports both standalone repos and monorepos where many
packages live under ``libs/<pkg>/``. Standalone repos keep working with no changes;
the sections below describe what's needed for a monorepo.

Top-level Jenkinsfile (provisioner)
-----------------------------------

The monorepo's root ``Jenkinsfile`` calls ``monorepo()`` to provision a
Multibranch Pipeline for each per-package Jenkinsfile. Run this on the default
branch only::

  @Library('vivarium_build_utils') _

  monorepo(
      jenkinsfiles: [
          'libs/core/Jenkinsfile',
          'libs/public-health/Jenkinsfile',
      ],
      // Jenkins credential ID for the GitHub App. Required, no default - vbu
      // stays org-agnostic so the literal UUID lives next to the org context.
      githubCredentialsId: 'fad62062-b1f4-447b-997f-005d6b1ea41e',
      folderPrefix: 'Public',  // optional, defaults to "Public"
  )

The provisioned pipelines land under ``<folderPrefix>/<repo>/libs/<pkg>/``.

Per-package Jenkinsfile
-----------------------

Each ``libs/<pkg>/Jenkinsfile`` calls ``reusable_pipeline()`` the same way a
standalone repo would, with one new argument::

  @Library('vivarium_build_utils') _

  reusable_pipeline(
      test_types: ['unit', 'integration'],
      deployable: true,
      env_reqs: 'ci_jenkins',  // pyproject.toml extra to install
  )

``env_reqs`` selects which ``[project.optional-dependencies]`` extra ``make
install`` pulls in. Omit it (or leave empty) on standalone repos to keep
base.mk's default of ``dev``.

In-tree sibling dependencies
----------------------------

By default ``make install`` resolves a package's dependencies - including
sibling packages in the same monorepo - from the package index. Set
``IN_TREE_SIBLINGS`` to a space-separated list of the packages whose source
changed in this PR to instead install *those* siblings from source, at the
*pending* version in each one's ``CHANGELOG.rst``::

  make install ENV_REQS=ci_github IN_TREE_SIBLINGS="engine config-tree"

This lets a single PR update interdependent packages without an intermediate
release: a dependent pinned as ``vivarium-engine>=<new>`` is satisfied by the
in-tree source - the pending version is reported through setuptools_scm's
pretend-version mechanism - rather than requiring ``<new>`` to be published
first.

Only siblings that are **all** of (1) listed in ``IN_TREE_SIBLINGS``, (2)
reachable from the package being installed (given ``ENV_REQS``), and (3) whose
pending version satisfies the version constraint placed on them are installed
from source; everything else - including *unchanged* siblings - resolves from
the index, so they are tested against their released versions. Condition (3)
means a changed sibling whose pending version is excluded by, say, an
upper-bound pin is left to the index rather than breaking the install.

Empty/unset (e.g. a plain local ``make install``) installs nothing from source,
and the whole mechanism is a no-op outside a ``libs/<pkg>/`` layout. CI passes
the diffed change set on the test/gating build; the release build omits it
entirely so published version pins are validated against the real index.

Tag prefix
----------

The ``TAG_PREFIX`` environment variable controls both ``make tag-version`` and
``make validate-tag``. It must be set consistently in both targets, or
``validate-tag`` will silently look at the wrong set of tags.

- Standalone repos: leave unset. Tags are ``v<X.Y.Z>``.
- Monorepo libs: set ``TAG_PREFIX=vivarium-<lib>-`` (e.g. ``vivarium-core-``).
  Tags become ``vivarium-<lib>-v<X.Y.Z>``.

Release workflows that invoke ``make validate-tag`` or ``make tag-version``
should export ``TAG_PREFIX`` before running them.

Fetching from internal Artifactory
----------------------------------

``IHME_PYPI`` defaults to the internal Artifactory URL and is woven into
``EXTRA_INDEX_FLAGS`` for ``make install``. Override it to empty
(``make install IHME_PYPI=``) in environments that can't reach IHME's network -
e.g. GitHub Actions runners. ``make deploy-package-artifactory`` requires a
non-empty ``IHME_PYPI`` and is Jenkins/internal-only.
