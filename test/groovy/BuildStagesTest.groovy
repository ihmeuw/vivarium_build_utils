import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

/**
 * Tests for build_stages.groovy, focusing on cleanup behavior.
 */
class BuildStagesTest extends BaseTest {

    def buildStages

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()

        // Mock stage
        helper.registerAllowedMethod('stage', [String, Closure], { String name, Closure body ->
            body()
        })

        // Mock cleanWs
        helper.registerAllowedMethod('cleanWs', [], { -> })

        // Mock dir
        helper.registerAllowedMethod('dir', [String, Closure], { String path, Closure body ->
            body()
        })

        // Mock deleteDir
        helper.registerAllowedMethod('deleteDir', [], { -> })

        // Mock withEnv
        helper.registerAllowedMethod('withEnv', [List, Closure], { List envs, Closure body ->
            body()
        })

        // Mock withCredentials
        helper.registerAllowedMethod('withCredentials', [List, Closure], { List creds, Closure body ->
            body()
        })

        // Mock publishHTML
        helper.registerAllowedMethod('publishHTML', [Map], { -> })

        // Mock parallel
        helper.registerAllowedMethod('parallel', [Map], { Map branches ->
            branches.each { name, closure -> closure() }
        })

        // Set up env vars that build_stages expects
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
        binding.setVariable('env', [
            TIMESTAMP: '2026-02-06',
            CRON_SCHEDULE: '',
            GIT_COMMIT: 'abc123',
            GIT_PREVIOUS_COMMIT: 'def456',
            ACTIVATE_BASE: 'source activate base',
            IS_CRON: 'false'
        ])
        binding.setVariable('params', [
            SKIP_DEPLOY: false,
            RUN_SLOW: false,
            SLACK_TO: '',
            DEBUG: false,
            FORCE_FULL_BUILD: false
        ])

        def script = loadScript('build_stages.groovy')
        buildStages = script()
    }

    @Test
    void 'cleanup does not require conda activation'() {
        buildStages.cleanup()

        // Verify 'make clean' was called
        assertTrue('make clean should be called', shCommandContains('make clean'))

        // Verify ACTIVATE is NOT in any sh command (cleanup shouldn't need conda)
        def cleanCommands = shCommands.findAll { it.script?.contains('make clean') }
        cleanCommands.each { cmd ->
            assertFalse(
                'cleanup should not require ACTIVATE',
                cmd.script.contains('source') && cmd.script.contains('activate')
            )
        }
    }

    @Test
    void 'cleanup runs make clean as plain command'() {
        buildStages.cleanup()

        def cleanCommand = shCommands.find { it.script?.contains('make clean') }
        assertNotNull('make clean command should exist', cleanCommand)
        assertEquals('make clean', cleanCommand.script)
    }

    @Test
    void 'runDebugInfo displays skip evaluation when provided'() {
        def skipEval = [
            previousBuildPassed: true,
            isDocOnlyChange: false,
            isChangelogOnlyChange: true,
            canSkipFullBuild: true,
            skipForDocOnly: false,
            skipForChangelogOnly: true
        ]

        buildStages.runDebugInfo(skipEval)

        assertTrue('Should display skip evaluation', echoContains('Skip Evaluation'))
        assertTrue(echoContains('previousBuildPassed'))
        assertTrue(echoContains('isChangelogOnlyChange'))
    }

    @Test
    void 'runDebugInfo works without skip evaluation'() {
        buildStages.runDebugInfo()

        assertTrue('Should display timestamp', echoContains('timestamp'))
        assertTrue('Should display parameters', echoContains('SKIP_DEPLOY'))
        assertTrue('Should display FORCE_FULL_BUILD', echoContains('FORCE_FULL_BUILD'))
    }

    @Test
    void 'runDebugInfo displays GIT_COMMIT and GIT_PREVIOUS_COMMIT'() {
        buildStages.runDebugInfo()

        assertTrue('Should display GIT_COMMIT', echoContains('GIT_COMMIT'))
        assertTrue('Should display GIT_PREVIOUS_COMMIT', echoContains('GIT_PREVIOUS_COMMIT'))
    }
}
