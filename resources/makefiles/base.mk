SHELL := /bin/bash
UTILS_DIR := $(dir $(abspath $(dir $(abspath $(dir $(lastword $(MAKEFILE_LIST)))))))
.DEFAULT_GOAL := list # If someone runs "make", run "make list"

# Source files to format, lint, and type check.
LOCATIONS=src tests

# Unless overridden, build conda environment using the package name.
SAFE_NAME = $(shell python -c "from pkg_resources import safe_name; print(safe_name(\"$(PACKAGE_NAME)\"))")

PACKAGE_VERSION = $(shell grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' CHANGELOG.rst | head -n 1 | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+')

# Use this URL to pull IHME Python packages and deploy this package to PyPi.
IHME_PYPI := https://artifactory.ihme.washington.edu/artifactory/api/pypi/pypi-shared/

# If CONDA_ENV_PATH is set (from a Jenkins build), use the -p flag when making Conda env in
# order to make env at specific path. Otherwise, make a named env at the default path using
# the -n flag.
PYTHON_VERSION ?= 3.11
CONDA_ENV_NAME ?= ${PACKAGE_NAME}_py${PYTHON_VERSION}
CONDA_ENV_CREATION_FLAG = $(if $(CONDA_ENV_PATH),-p ${CONDA_ENV_PATH},-n ${CONDA_ENV_NAME})

# These are the doc and source code files in this repo.
# When one of these files changes, it means that Make targets need to run again.
MAKE_SOURCES := $(shell find . -type d -name "*" ! -path "./.git*" ! -path "./.vscode" ! -path "./output" ! -path "./output/*" ! -path "./archive" ! -path "./dist" ! -path "./output/htmlcov*" ! -path "**/.pytest_cache*" ! -path "**/__pycache__" ! -path "./output/docs_build*" ! -path "./.pytype*" ! -path "." ! -path "./src/${PACKAGE_NAME}/legacy*" ! -path ./.history ! -path "./.history/*" ! -path "./src/${PACKAGE_NAME}.egg-info" ! -path ./.idea ! -path "./.idea/*" )

# Phony targets don't produce artifacts.
.PHONY: .list-targets build-env build-doc format lint mypy integration build-package clean debug deploy-doc deploy-package full help list quick install install-upstream-deps

###############################
# Help and debugging commands #
###############################

# List of Make targets is generated dynamically. Note that to have a target show up in this
# list, it must have an in-line comment starting with a '#' on the target definition,
# e.g. some-target:  # this is the description for some-target
list: # Print available Make targets
	@echo
	@echo "Make targets:"
	@for file in Makefile $(UTILS_DIR)resources/makefiles/*.mk; do \
		grep -i "^[a-zA-Z][a-zA-Z0-9_ \.\-]*: .*[#].*" $$file | sort | sed 's/:.*#/ : /g'; \
	done | column -t -s:
	@echo

debug: # Print debug information
	@echo
	@echo "'make' invoked with these environment variables:"
	@echo "CONDA_ENV_NAME:                   ${CONDA_ENV_NAME}"
	@echo "PYTHON_VERSION:				     ${PYTHON_VERSION}"
	@echo "IHME_PYPI:                        ${IHME_PYPI}"
	@echo "LOCATIONS:                        ${LOCATIONS}"
	@echo "PACKAGE_NAME:                     ${PACKAGE_NAME}"
	@echo "PACKAGE_VERSION:                  ${PACKAGE_VERSION}"
	@echo "PYPI_ARTIFACTORY_CREDENTIALS_USR: ${PYPI_ARTIFACTORY_CREDENTIALS_USR} "
	@echo
	@echo "Make sources:                     ${MAKE_SOURCES}"
	@echo
	@echo "vivarium_build_utils version:     $(shell python -c "import importlib.metadata; print(importlib.metadata.version('vivarium_build_utils'))" 2>/dev/null || echo "unknown")"

##########################
# Jenkins build commands #
##########################

create-env: # Create a new conda environment with name {PACKAGE_NAME}_py{PYTHON_VERSION}.
	conda create ${CONDA_ENV_CREATION_FLAG} python=${PYTHON_VERSION} --yes

install: ENV_REQS?=dev
install: # Install package and dependencies
	pip install uv
	uv pip install --upgrade pip setuptools 
	uv pip install -e .[${ENV_REQS}] --extra-index-url ${IHME_PYPI}simple/ --index-strategy unsafe-best-match

lint: # Check for formatting errors
# NOTE: This is not precisely 'linting' but we historically use that term here
	isort $(LOCATIONS) --check --verbose --only-modified --diff
	black $(LOCATIONS) --check --diff

mypy: # Check for type hinting errors
	mypy --config-file pyproject.toml .

# test: test commands are defined in test.mk

build-doc: $(MAKE_SOURCES) # Build documentation
	$(MAKE) -C docs/ html SPHINXOPTS="-T -W --keep-going"
	@echo "Ignore, Created by Makefile, `date`" > $@

test-doc: $(MAKE_SOURCES) # Test documentation examples
	$(MAKE) doctest -C docs/

tag-version: # Tag current version and push to git
	git tag -a "v${PACKAGE_VERSION}" -m "Tag automatically generated from Jenkins."
	git push --tags

build-package: $(MAKE_SOURCES) # Build pip wheel package
	pip install build
	python -m build
	@echo "Ignore, Created by Makefile, `date`" > $@

deploy-package-artifactory: # Deploy the package to Artifactory
	@[ "${PYPI_ARTIFACTORY_CREDENTIALS_USR}" ] && echo "" > /dev/null || ( echo "PYPI_ARTIFACTORY_CREDENTIALS_USR is not set, export using simsci artifactory credentials"; exit 1 )
	@[ "${PYPI_ARTIFACTORY_CREDENTIALS_PSW}" ] && echo "" > /dev/null || ( echo "PYPI_ARTIFACTORY_CREDENTIALS_PSW is not set, export using simsci artifactory credentials"; exit 1 )
	pip install twine
	twine upload --repository-url ${IHME_PYPI} -u ${PYPI_ARTIFACTORY_CREDENTIALS_USR} -p ${PYPI_ARTIFACTORY_CREDENTIALS_PSW} dist/*

deploy-doc: # Deploy documentation to shared server
	@[ "${DOCS_ROOT_PATH}" ] && echo "" > /dev/null || ( echo "DOCS_ROOT_PATH is not set"; exit 1 )
	mkdir -m 0775 -p ${DOCS_ROOT_PATH}/${PACKAGE_NAME}/${PACKAGE_VERSION}
	cp -R ./output/docs_build/* ${DOCS_ROOT_PATH}/${PACKAGE_NAME}/${PACKAGE_VERSION}
	chmod -R 0775 ${DOCS_ROOT_PATH}/${PACKAGE_NAME}/${PACKAGE_VERSION}
	cd ${DOCS_ROOT_PATH}/${PACKAGE_NAME} && ln -nsFfv ${PACKAGE_VERSION} current

clean: # Clean build artifacts and temporary files
	@rm -rf format build-doc build-package integration .pytest_cache
	@rm -rf dist output
	$(shell find . -type f -name '*py[co]' -delete -o -type d -name __pycache__ -delete)

#########################
# Other/helper commands #
#########################

build-env: # Create a new conda environment with name {PACKAGE_NAME}_py{PYTHON_VERSION} and install packages
	make create-env CONDA_ENV_NAME=$(CONDA_ENV_NAME)
	conda run -n $(CONDA_ENV_NAME) make install

install-upstream-deps: # Install upstream dependencies
	@echo "Contents of install_dependency_branch.sh"
	@echo "----------------------------------------"
	@cat $(UTILS_DIR)/resources/scripts/install_dependency_branch.sh
	@echo
	@echo "----------------------------------------"
	@sh $(UTILS_DIR)/resources/scripts/install_dependency_branch.sh $(DEPENDENCY_NAME) $(BRANCH_NAME) $(WORKFLOW)

format: setup.py pyproject.toml $(MAKE_SOURCES) # Format code (isort and black)
	isort $(LOCATIONS)
	black $(LOCATIONS)
	@echo "Ignore, Created by Makefile, `date`" > $@

manual-deploy: # Deploy package (build, tag, and deploy)
	@[ "${PYPI_ARTIFACTORY_CREDENTIALS_USR}" ] && echo "" > /dev/null || ( echo "PYPI_ARTIFACTORY_CREDENTIALS_USR is not set, export using simsci artifactory credentials"; exit 1 )
	@[ "${PYPI_ARTIFACTORY_CREDENTIALS_PSW}" ] && echo "" > /dev/null || ( echo "PYPI_ARTIFACTORY_CREDENTIALS_PSW is not set, export using simsci artifactory credentials"; exit 1 )
	make build-env
	conda run -n $(CONDA_ENV_NAME) make build-package
	conda run -n $(CONDA_ENV_NAME) make tag-version
	conda run -n $(CONDA_ENV_NAME) make deploy-package-artifactory
