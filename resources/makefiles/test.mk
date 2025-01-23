define test
	export COVERAGE_FILE=./output/.coverage.$(1) && \
	pytest -vvv --cov --cov-report term --cov-report html:./output/htmlcov_$(1) tests/$(1)/
endef

define test-runslow
	export COVERAGE_FILE=./output/.coverage.$(1) && \
	pytest -vvv --runslow --cov --cov-report term --cov-report html:./output/htmlcov_$(1) tests/$(1)/
endef

e2e: $(MAKE_SOURCES) # Run end-to-end tests
	$(call test,e2e)

e2e-runslow: $(MAKE_SOURCES) # Run end-to-end tests with slow tests
	$(call test-runslow,e2e)

integration: $(MAKE_SOURCES) # Run integration tests
	$(call test,integration)

integration-runslow: $(MAKE_SOURCES) # Run integration tests with slow tests
	$(call test-runslow,integration)

unit: $(MAKE_SOURCES) # Run unit tests
	$(call test,unit)

unit-runslow: $(MAKE_SOURCES) # Run unit tests with slow tests
	$(call test-runslow,unit)