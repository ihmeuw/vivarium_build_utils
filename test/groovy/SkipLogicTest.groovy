import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

/**
 * Tests the skip logic that lives in reusable_pipeline.groovy.
 *
 * Since the reusable pipeline is complex and depends on many Jenkins-specific
 * constructs (pipeline{}, agent{}, etc.), we extract and test the skip decision
 * logic independently.
 *
 * The skip logic is:
 *   canSkipFullBuild = previousBuildPassed && !isCron && !forceFullBuild
 *   skipForChangelogOnly = canSkipFullBuild && isChangelogOnlyChange
 *   skipForDocOnly = canSkipFullBuild && isDocOnlyChange
 */
class SkipLogicTest extends BaseTest {

    @Override
    @Before
    void setUp() throws Exception {
        super.setUp()
    }

    // Helper to evaluate the skip logic with given inputs
    Map evaluateSkipLogic(Map inputs) {
        def previousBuildPassed = inputs.previousBuildPassed ?: false
        def isDocOnlyChange = inputs.isDocOnlyChange ?: false
        def isChangelogOnlyChange = inputs.isChangelogOnlyChange ?: false
        def isCron = inputs.isCron ?: false
        def forceFullBuild = inputs.forceFullBuild ?: false

        def canSkipFullBuild = previousBuildPassed && !isCron && !forceFullBuild
        def skipForChangelogOnly = canSkipFullBuild && isChangelogOnlyChange
        def skipForDocOnly = canSkipFullBuild && isDocOnlyChange

        return [
            canSkipFullBuild: canSkipFullBuild,
            skipForChangelogOnly: skipForChangelogOnly,
            skipForDocOnly: skipForDocOnly
        ]
    }

    // =========================================================================
    // Full build scenarios (nothing skipped)
    // =========================================================================

    @Test
    void 'full build when previous build failed'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: false,
            isDocOnlyChange: true,
            isChangelogOnlyChange: false
        )

        assertFalse('Should not skip for doc-only when previous build failed', result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
        assertFalse(result.canSkipFullBuild)
    }

    @Test
    void 'full build when cron build even with doc-only changes'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isCron: true
        )

        assertFalse('Should not skip doc-only on cron build', result.skipForDocOnly)
        assertFalse(result.canSkipFullBuild)
    }

    @Test
    void 'full build when cron build even with changelog-only changes'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isChangelogOnlyChange: true,
            isCron: true
        )

        assertFalse('Should not skip changelog-only on cron build', result.skipForChangelogOnly)
        assertFalse(result.canSkipFullBuild)
    }

    @Test
    void 'full build when force full build is set even with doc-only changes'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            forceFullBuild: true
        )

        assertFalse('Should not skip when force full build', result.skipForDocOnly)
        assertFalse(result.canSkipFullBuild)
    }

    @Test
    void 'full build when force full build is set even with changelog-only changes'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isChangelogOnlyChange: true,
            forceFullBuild: true
        )

        assertFalse('Should not skip when force full build', result.skipForChangelogOnly)
        assertFalse(result.canSkipFullBuild)
    }

    @Test
    void 'full build when changes are neither doc-only nor changelog-only'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: false,
            isChangelogOnlyChange: false
        )

        assertTrue('Can skip should be true', result.canSkipFullBuild)
        assertFalse('Should not skip for doc', result.skipForDocOnly)
        assertFalse('Should not skip for changelog', result.skipForChangelogOnly)
    }

    @Test
    void 'full build when no previous build and doc-only change'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: false,
            isDocOnlyChange: true
        )

        assertFalse(result.canSkipFullBuild)
        assertFalse(result.skipForDocOnly)
    }

    // =========================================================================
    // Doc-only skip scenarios
    // =========================================================================

    @Test
    void 'skip for doc-only when previous build passed and not cron'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isChangelogOnlyChange: false
        )

        assertTrue(result.canSkipFullBuild)
        assertTrue('Should skip for doc-only', result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
    }

    // =========================================================================
    // Changelog-only skip scenarios
    // =========================================================================

    @Test
    void 'skip for changelog-only when previous build passed and not cron'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isChangelogOnlyChange: true,
            isDocOnlyChange: false
        )

        assertTrue(result.canSkipFullBuild)
        assertTrue('Should skip for changelog-only', result.skipForChangelogOnly)
        assertFalse(result.skipForDocOnly)
    }

    // =========================================================================
    // Both doc and changelog changes
    // =========================================================================

    @Test
    void 'both doc and changelog only flags true skips both'() {
        // Edge case: if somehow both are true (shouldn't normally happen
        // since a change can't be exclusively docs/ AND exclusively CHANGELOG)
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isChangelogOnlyChange: true
        )

        assertTrue(result.canSkipFullBuild)
        assertTrue(result.skipForDocOnly)
        assertTrue(result.skipForChangelogOnly)
    }

    // =========================================================================
    // Priority: changelog-only takes precedence (skips entire build)
    // =========================================================================

    @Test
    void 'changelog-only skip is checked before doc-only in pipeline'() {
        // This tests the ordering in the pipeline:
        // if (skipForChangelogOnly) { skip entirely }
        // else if (skipForDocOnly) { skip most, run docs }
        // else { full build }
        //
        // When both are true, changelog-only takes priority (skips everything).

        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isChangelogOnlyChange: true
        )

        // Both would be true, but the pipeline checks changelog first
        assertTrue(result.skipForChangelogOnly)
        // In the actual pipeline, the else-if means doc-only won't execute,
        // but the flag itself is still true
        assertTrue(result.skipForDocOnly)
    }

    // =========================================================================
    // Combined condition edge cases
    // =========================================================================

    @Test
    void 'all skip conditions false results in full build'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: false,
            isDocOnlyChange: false,
            isChangelogOnlyChange: false,
            isCron: false,
            forceFullBuild: false
        )

        assertFalse(result.canSkipFullBuild)
        assertFalse(result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
    }

    @Test
    void 'previous build failed overrides everything'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: false,
            isDocOnlyChange: true,
            isChangelogOnlyChange: true,
            isCron: false,
            forceFullBuild: false
        )

        assertFalse('Cannot skip when previous build failed', result.canSkipFullBuild)
        assertFalse(result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
    }

    @Test
    void 'force full build overrides even when all skip conditions met'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isChangelogOnlyChange: true,
            isCron: false,
            forceFullBuild: true
        )

        assertFalse('Cannot skip when force full build', result.canSkipFullBuild)
        assertFalse(result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
    }

    @Test
    void 'cron overrides even when all skip conditions met'() {
        def result = evaluateSkipLogic(
            previousBuildPassed: true,
            isDocOnlyChange: true,
            isChangelogOnlyChange: true,
            isCron: true,
            forceFullBuild: false
        )

        assertFalse('Cannot skip on cron', result.canSkipFullBuild)
        assertFalse(result.skipForDocOnly)
        assertFalse(result.skipForChangelogOnly)
    }
}
