import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

/**
 * Integration-style test that simulates the changelog-only skip path
 * from reusable_pipeline.groovy.
 *
 * We exercise the actual var scripts (previous_build_passed, git_utils)
 * in the same sequence as the pipeline to find where failures originate.
 */
class ChangelogOnlySkipTest extends BaseTest {

    def gitUtils
    def previousBuildPassedScript

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        gitUtils = loadScript('git_utils.groovy')
        previousBuildPassedScript = loadScript('previous_build_passed.groovy')
    }

    /**
     * Helper: wire up the environment so that the pipeline would see a
     * changelog-only change with a previously successful build.
     */
    private void setupChangelogOnlyScenario() {
        // Simulate a successful previous build
        def mockPreviousBuild = [
            result: 'SUCCESS',
            number: 41,
            getBuildVariables: { -> return ['GIT_COMMIT': 'prev_abc123'] }
        ]
        binding.setVariable('currentBuild', [
            previousBuild: mockPreviousBuild,
            result: null,        // no result yet
            number: 42
        ])

        // Simulate Jenkins Git plugin environment after checkout
        def env = [
            GIT_PREVIOUS_COMMIT: 'prev_abc123',
            GIT_COMMIT: 'current_def456',
            IS_CRON: 'false'
        ]
        binding.setVariable('env', env)

        // The diff only contains CHANGELOG.rst
        mockShReturn('cat-file', 'exists')
        mockShReturn('git diff --name-only', 'CHANGELOG.rst')
        // grep -v '^CHANGELOG' on 'CHANGELOG.rst' → 0 non-matching lines (all match)
        mockShReturn("grep -v '^CHANGELOG'", '0')
        // grep -v '^docs/' on 'CHANGELOG.rst' → 1 non-matching line (doesn't match docs/)
        mockShReturn("grep -v '^docs/'", '1')

        // No force-full-build
        binding.setVariable('params', [FORCE_FULL_BUILD: false])
    }

    // =========================================================================
    // Step 1: previous_build_passed() should return true
    // =========================================================================

    @Test
    void 'step1 - previous build passed returns true for SUCCESS'() {
        setupChangelogOnlyScenario()

        def result = previousBuildPassedScript()

        assertTrue('previous_build_passed should return true', result)
        assertTrue(echoContains('SUCCESS'))
    }

    // =========================================================================
    // Step 2: git_utils.isChangeOnlyMatching should detect changelog-only
    // =========================================================================

    @Test
    void 'step2 - isChangeOnlyMatching detects changelog-only change'() {
        setupChangelogOnlyScenario()

        def result = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')

        assertTrue('Should detect changelog-only change', result)
        assertTrue(echoContains('All changes are changelog-only'))
    }

    @Test
    void 'step2b - isChangeOnlyMatching returns false for docs pattern on CHANGELOG'() {
        setupChangelogOnlyScenario()

        def result = gitUtils.isChangeOnlyMatching('^docs/', 'docs-only')

        assertFalse('CHANGELOG.rst should NOT match docs/ pattern', result)
    }

    // =========================================================================
    // Step 3: Full skip decision logic (mirrors reusable_pipeline.groovy)
    // =========================================================================

    @Test
    void 'step3 - full skip decision marks changelog-only skip'() {
        setupChangelogOnlyScenario()

        // Reproduce the exact logic from reusable_pipeline.groovy
        def previousBuildPassed = previousBuildPassedScript()
        def isDocOnlyChange = gitUtils.isChangeOnlyMatching('^docs/', 'docs-only')
        def isChangelogOnlyChange = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')
        def isCron = binding.getVariable('env').IS_CRON.toBoolean()
        def forceFullBuild = binding.getVariable('params').FORCE_FULL_BUILD

        def canSkipFullBuild = previousBuildPassed && !isCron && !forceFullBuild
        def skipForChangelogOnly = canSkipFullBuild && isChangelogOnlyChange
        def skipForDocOnly = canSkipFullBuild && isDocOnlyChange

        assertTrue('previousBuildPassed should be true', previousBuildPassed)
        assertFalse('isDocOnlyChange should be false', isDocOnlyChange)
        assertTrue('isChangelogOnlyChange should be true', isChangelogOnlyChange)
        assertFalse('isCron should be false', isCron)
        assertFalse('forceFullBuild should be false', forceFullBuild)
        assertTrue('canSkipFullBuild should be true', canSkipFullBuild)
        assertTrue('skipForChangelogOnly should be true', skipForChangelogOnly)
        assertFalse('skipForDocOnly should be false', skipForDocOnly)
    }

    // =========================================================================
    // Step 4: Verify the changelog-only branch doesn't throw
    // =========================================================================

    @Test
    void 'step4 - changelog-only skip path sets SUCCESS and echoes message'() {
        setupChangelogOnlyScenario()

        def previousBuildPassed = previousBuildPassedScript()
        def isChangelogOnlyChange = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')
        def canSkipFullBuild = previousBuildPassed && !binding.getVariable('env').IS_CRON.toBoolean() && !binding.getVariable('params').FORCE_FULL_BUILD
        def skipForChangelogOnly = canSkipFullBuild && isChangelogOnlyChange

        assertTrue('Should skip for changelog-only', skipForChangelogOnly)

        // Simulate the code in the if(skipForChangelogOnly) block
        if (skipForChangelogOnly) {
            echoMessages << "This is a changelog-only change since last build and previous build passed. Skipping entire build."
            binding.getVariable('currentBuild').result = 'SUCCESS'
        }

        assertEquals('SUCCESS', binding.getVariable('currentBuild').result)
        assertTrue(echoContains('Skipping entire build'))
    }

    // =========================================================================
    // Step 5: Verify cleanup() works after changelog-only skip
    // =========================================================================

    @Test
    void 'step5 - cleanup after changelog-only skip does not fail'() {
        setupChangelogOnlyScenario()

        // Set up the extra env vars that build_stages needs
        binding.setVariable('PYTHON_VERSION', '3.11')
        binding.setVariable('CONDA_ENV_PATH', '/tmp/test-env')
        binding.setVariable('ACTIVATE', 'source activate test')
        binding.setVariable('ACTIVATE_BASE', 'source activate base')
        binding.setVariable('WORKSPACE', '/tmp/workspace')
        binding.setVariable('NODE_NAME', 'test-node')
        binding.setVariable('EXECUTOR_NUMBER', '0')
        binding.setVariable('BUILD_NUMBER', '1')
        binding.setVariable('BRANCH', 'main')
        binding.setVariable('CONDARC', '/tmp/.condarc')
        binding.setVariable('CONDA_BIN_PATH', '/tmp/bin')
        binding.setVariable('CONDA_ENV_NAME', 'test-env')
        binding.setVariable('GIT_BRANCH', 'main')
        binding.setVariable('JOB_NAME', 'test-job')
        binding.setVariable('XDG_CACHE_HOME', '/tmp/cache')
        binding.setVariable('IS_CRON', 'false')
        binding.getVariable('env').putAll([
            TIMESTAMP: '2026-02-09',
            CRON_SCHEDULE: '',
            ACTIVATE_BASE: 'source activate base'
        ])
        binding.getVariable('params').putAll([
            SKIP_DEPLOY: false,
            RUN_SLOW: false,
            SLACK_TO: '',
            DEBUG: false
        ])

        helper.registerAllowedMethod('stage', [String, Closure], { String name, Closure body -> body() })
        helper.registerAllowedMethod('cleanWs', [], { -> })
        helper.registerAllowedMethod('dir', [String, Closure], { String path, Closure body -> body() })
        helper.registerAllowedMethod('deleteDir', [], { -> })

        def buildStagesScript = loadScript('build_stages.groovy')
        def buildStages = buildStagesScript()

        // Simulate the changelog-only path and then cleanup (like the finally block)
        def skipForChangelogOnly = true
        if (skipForChangelogOnly) {
            echoMessages << "Skipping entire build."
            binding.getVariable('currentBuild').result = 'SUCCESS'
        }

        // Now run cleanup as the finally block would
        buildStages.cleanup()

        assertTrue('make clean should have been called', shCommandContains('make clean'))
        assertEquals('Build result should still be SUCCESS', 'SUCCESS', binding.getVariable('currentBuild').result)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    void 'changelog plus non-changelog file is NOT changelog-only'() {
        setupChangelogOnlyScenario()
        // Override the diff to include a non-changelog file
        shReturnValues.remove('git diff --name-only')
        mockShReturn('git diff --name-only', 'CHANGELOG.rst\nsrc/main.py')
        mockShReturn('grep -v', '1')  // 1 non-matching line

        def result = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')

        assertFalse('Should NOT be changelog-only when other files changed', result)
    }

    @Test
    void 'empty GIT_PREVIOUS_COMMIT triggers full build'() {
        // No previous commit available
        binding.setVariable('env', [
            GIT_PREVIOUS_COMMIT: null,
            GIT_COMMIT: 'current_def456',
            IS_CRON: 'false'
        ])
        binding.setVariable('currentBuild', [previousBuild: null, result: null])

        def result = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')

        assertFalse('Should trigger full build when no previous commit', result)
        assertTrue(echoContains('No changed files found since last build'))
    }

    @Test
    void 'shallow clone without previous commit triggers full build'() {
        setupChangelogOnlyScenario()
        // Override cat-file to indicate the commit is missing
        shReturnValues.clear()
        mockShReturn('cat-file', 'missing')

        def result = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')

        assertFalse('Should trigger full build when commit not in clone', result)
        assertTrue(echoContains('not found in current clone'))
    }
}
