/**
 * Check if all changes since the last Jenkins build are changelog-only.
 * 
 * This function compares the current commit against the commit from the
 * previous Jenkins build (not just HEAD~1) to determine if only CHANGELOG
 * files have been modified.
 * 
 * Returns false (run full build) if:
 * - No previous build exists (first build)
 * - The previous build's commit cannot be determined
 * - There are no changed files (empty commit)
 * - Any non-CHANGELOG files have been modified
 * 
 * Returns true (can skip non-essential steps) if:
 * - All changed files since the last build are CHANGELOG files
 */
def call() {
    def changedFiles = git_utils.getChangedFilesSinceLastBuild()
    
    // If no files are found (first build, shallow clone, or empty commit),
    // return false to trigger a full build
    if (changedFiles == '') {
        echo "No changed files found since last build. Running full build."
        return false
    }

    echo "Files changed since last build:\n${changedFiles}"

    // Check if all changed files are CHANGELOG files
    def hasNonChangelogChanges = sh(
        script: """
            echo '${changedFiles}' |
            grep -v '^CHANGELOG' |
            wc -l || echo '0'
        """,
        returnStdout: true
    ).trim().toInteger() > 0
    
    if (!hasNonChangelogChanges) {
        echo "All changes are changelog-only."
    }
    
    // Return true if there are only changes to the changelog
    return !hasNonChangelogChanges
}
