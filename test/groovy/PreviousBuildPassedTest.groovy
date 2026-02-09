import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

class PreviousBuildPassedTest extends BaseTest {

    def previousBuildPassed

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        previousBuildPassed = loadScript('previous_build_passed.groovy')
    }

    @Test
    void 'returns false when no previous build exists'() {
        binding.setVariable('currentBuild', [previousBuild: null])

        def result = previousBuildPassed()

        assertFalse(result)
        assertTrue(echoContains('No previous build found'))
    }

    @Test
    void 'returns true when previous build was SUCCESS'() {
        def mockPreviousBuild = [result: 'SUCCESS', number: 42]
        binding.setVariable('currentBuild', [previousBuild: mockPreviousBuild])

        def result = previousBuildPassed()

        assertTrue(result)
    }

    @Test
    void 'returns false when previous build was FAILURE'() {
        def mockPreviousBuild = [result: 'FAILURE', number: 42]
        binding.setVariable('currentBuild', [previousBuild: mockPreviousBuild])

        def result = previousBuildPassed()

        assertFalse(result)
        assertTrue(echoContains('Previous build did not pass'))
    }

    @Test
    void 'returns false when previous build was UNSTABLE'() {
        def mockPreviousBuild = [result: 'UNSTABLE', number: 42]
        binding.setVariable('currentBuild', [previousBuild: mockPreviousBuild])

        def result = previousBuildPassed()

        assertFalse(result)
    }

    @Test
    void 'returns false when previous build was ABORTED'() {
        def mockPreviousBuild = [result: 'ABORTED', number: 42]
        binding.setVariable('currentBuild', [previousBuild: mockPreviousBuild])

        def result = previousBuildPassed()

        assertFalse(result)
    }

    @Test
    void 'returns false when previous build result is null'() {
        def mockPreviousBuild = [result: null, number: 42]
        binding.setVariable('currentBuild', [previousBuild: mockPreviousBuild])

        def result = previousBuildPassed()

        assertFalse(result)
    }
}
