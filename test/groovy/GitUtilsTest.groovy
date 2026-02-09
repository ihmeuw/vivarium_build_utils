import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

class GitUtilsTest extends BaseTest {

    def gitUtils

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
        gitUtils = loadScript('git_utils.groovy')
    }

    // =========================================================================
    // getPreviousBuildCommit()
    // =========================================================================

    @Test
    void 'getPreviousBuildCommit returns GIT_PREVIOUS_COMMIT when set'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123']
        binding.setVariable('env', env)

        def result = gitUtils.getPreviousBuildCommit()

        assertEquals('abc123', result)
        assertTrue(echoContains('Using GIT_PREVIOUS_COMMIT: abc123'))
    }

    @Test
    void 'getPreviousBuildCommit falls back to previous build variables'() {
        def env = [GIT_PREVIOUS_COMMIT: null]
        binding.setVariable('env', env)

        def mockPreviousBuild = [
            getBuildVariables: { -> return ['GIT_COMMIT': 'def456'] }
        ]
        def mockCurrentBuild = [previousBuild: mockPreviousBuild]
        binding.setVariable('currentBuild', mockCurrentBuild)

        def result = gitUtils.getPreviousBuildCommit()

        assertEquals('def456', result)
        assertTrue(echoContains("Using previous build's GIT_COMMIT: def456"))
    }

    @Test
    void 'getPreviousBuildCommit returns null when no previous build exists'() {
        def env = [GIT_PREVIOUS_COMMIT: null]
        binding.setVariable('env', env)

        def mockCurrentBuild = [previousBuild: null]
        binding.setVariable('currentBuild', mockCurrentBuild)

        def result = gitUtils.getPreviousBuildCommit()

        assertNull(result)
        assertTrue(echoContains('No previous build commit found'))
    }

    @Test
    void 'getPreviousBuildCommit returns null when previous build has no GIT_COMMIT'() {
        def env = [GIT_PREVIOUS_COMMIT: null]
        binding.setVariable('env', env)

        def mockPreviousBuild = [
            getBuildVariables: { -> return [:] }
        ]
        def mockCurrentBuild = [previousBuild: mockPreviousBuild]
        binding.setVariable('currentBuild', mockCurrentBuild)

        def result = gitUtils.getPreviousBuildCommit()

        assertNull(result)
    }

    @Test
    void 'getPreviousBuildCommit handles exception from getBuildVariables'() {
        def env = [GIT_PREVIOUS_COMMIT: null]
        binding.setVariable('env', env)

        def mockPreviousBuild = [
            getBuildVariables: { -> throw new RuntimeException('Permission denied') }
        ]
        def mockCurrentBuild = [previousBuild: mockPreviousBuild]
        binding.setVariable('currentBuild', mockCurrentBuild)

        def result = gitUtils.getPreviousBuildCommit()

        assertNull(result)
        assertTrue(echoContains('Could not get previous build commit'))
    }

    // =========================================================================
    // getChangedFilesSinceLastBuild()
    // =========================================================================

    @Test
    void 'getChangedFilesSinceLastBuild returns empty when no previous commit'() {
        def env = [GIT_PREVIOUS_COMMIT: null, GIT_COMMIT: 'current123']
        binding.setVariable('env', env)
        binding.setVariable('currentBuild', [previousBuild: null])

        def result = gitUtils.getChangedFilesSinceLastBuild()

        assertEquals('', result)
        assertTrue(echoContains('No previous build commit found'))
    }

    @Test
    void 'getChangedFilesSinceLastBuild returns empty when commit not in clone'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123', GIT_COMMIT: 'current123']
        binding.setVariable('env', env)

        mockShReturn('cat-file', 'missing')

        def result = gitUtils.getChangedFilesSinceLastBuild()

        assertEquals('', result)
        assertTrue(echoContains('not found in current clone'))
    }

    @Test
    void 'getChangedFilesSinceLastBuild returns changed files'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123', GIT_COMMIT: 'current123']
        binding.setVariable('env', env)

        mockShReturn('cat-file', 'exists')
        mockShReturn('git diff --name-only', 'src/main.py\nREADME.md')

        def result = gitUtils.getChangedFilesSinceLastBuild()

        assertEquals('src/main.py\nREADME.md', result)
    }

    // =========================================================================
    // isChangeOnlyMatching()
    // =========================================================================

    @Test
    void 'isChangeOnlyMatching returns false when no changed files'() {
        def env = [GIT_PREVIOUS_COMMIT: null, GIT_COMMIT: 'current123']
        binding.setVariable('env', env)
        binding.setVariable('currentBuild', [previousBuild: null])

        def result = gitUtils.isChangeOnlyMatching('^docs/', 'docs-only')

        assertFalse(result)
        assertTrue(echoContains('No changed files found since last build'))
    }

    @Test
    void 'isChangeOnlyMatching returns true when all files match pattern'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123', GIT_COMMIT: 'current123']
        binding.setVariable('env', env)

        mockShReturn('cat-file', 'exists')
        mockShReturn('git diff --name-only', 'docs/guide.rst\ndocs/api.rst')
        mockShReturn('grep -v', '0')  // wc -l returns 0 non-matching

        def result = gitUtils.isChangeOnlyMatching('^docs/', 'docs-only')

        assertTrue(result)
        assertTrue(echoContains('All changes are docs-only'))
    }

    @Test
    void 'isChangeOnlyMatching returns false when some files do not match'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123', GIT_COMMIT: 'current123']
        binding.setVariable('env', env)

        mockShReturn('cat-file', 'exists')
        mockShReturn('git diff --name-only', 'docs/guide.rst\nsrc/main.py')
        mockShReturn('grep -v', '1')  // wc -l returns 1 non-matching

        def result = gitUtils.isChangeOnlyMatching('^docs/', 'docs-only')

        assertFalse(result)
    }

    @Test
    void 'isChangeOnlyMatching works for changelog pattern'() {
        def env = [GIT_PREVIOUS_COMMIT: 'abc123', GIT_COMMIT: 'current123']
        binding.setVariable('env', env)

        mockShReturn('cat-file', 'exists')
        mockShReturn('git diff --name-only', 'CHANGELOG.rst')
        mockShReturn('grep -v', '0')

        def result = gitUtils.isChangeOnlyMatching('^CHANGELOG', 'changelog-only')

        assertTrue(result)
        assertTrue(echoContains('All changes are changelog-only'))
    }
}
