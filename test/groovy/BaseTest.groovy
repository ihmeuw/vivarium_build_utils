import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before

/**
 * Base class for testing Jenkins shared library vars.
 *
 * Provides common setup for mocking Jenkins pipeline steps and environment.
 */
class BaseTest extends BasePipelineTest {

    // Collected echo messages for assertions
    List<String> echoMessages = []
    // Collected sh commands for assertions
    List<Map> shCommands = []
    // Map of sh command patterns to their return values
    Map<String, String> shReturnValues = [:]

    @Override
    @Before
    void setUp() throws Exception {
        // Configure script roots BEFORE calling super.setUp()
        scriptRoots += 'vars'
        super.setUp()

        // Clear tracking lists
        echoMessages = []
        shCommands = []
        shReturnValues = [:]

        // Mock echo
        helper.registerAllowedMethod('echo', [String], { String msg ->
            echoMessages << msg
        })

        // Mock sh with map argument (returnStdout, script, etc.)
        helper.registerAllowedMethod('sh', [Map], { Map args ->
            shCommands << args
            if (args.returnStdout) {
                // Find the LAST matching pattern (most recently registered takes priority)
                def match = null
                shReturnValues.each { pattern, value ->
                    if (args.script.contains(pattern)) {
                        match = value
                    }
                }
                return match ?: ''
            }
            return null
        })

        // Mock sh with string argument
        helper.registerAllowedMethod('sh', [String], { String cmd ->
            shCommands << [script: cmd, returnStdout: false]
            return null
        })
    }

    /**
     * Register a shell command pattern to return a specific value.
     * When sh() is called with returnStdout:true and the script contains
     * the pattern, the registered value will be returned.
     */
    void mockShReturn(String pattern, String returnValue) {
        shReturnValues[pattern] = returnValue
    }

    /**
     * Set up mock environment variables.
     */
    void setEnvVar(String name, String value) {
        binding.setVariable('env', (binding.getVariable('env') ?: [:]) + [(name): value])
    }

    /**
     * Check if a specific echo message was output.
     */
    boolean echoContains(String substring) {
        return echoMessages.any { it.contains(substring) }
    }

    /**
     * Check if a specific sh command was run.
     */
    boolean shCommandContains(String substring) {
        return shCommands.any { it.script?.contains(substring) }
    }
}
