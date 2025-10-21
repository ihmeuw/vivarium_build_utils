def call() {
    try {
        // First, try to ensure we have enough history for the comparison
        sh(script: "git fetch --depth=10 origin || true", returnStdout: false)
        
        // Check if the latest commit only contains changelog changes
        def changedFiles = sh(
            script: "git diff --name-only HEAD~1 HEAD 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        // If no files are found, could be shallow clone or first commit
        if (changedFiles == '') {
            echo "Unable to determine changed files in latest commit, proceeding with full build"
            return false
        }

        // Check if all changed files in the latest commit are CHANGELOG files
        def hasNonChangelogChanges = sh(
            script: """
                git diff --name-only HEAD~1 HEAD 2>/dev/null |
                grep -v '^CHANGELOG' |
                wc -l || echo '0'
            """,
            returnStdout: true
        ).trim().toInteger() > 0
        
        echo "Changed files in latest commit: ${changedFiles.split('\n').join(', ')}"
        echo "Has non-changelog changes: ${hasNonChangelogChanges}"
        
        // Return true if there are only changes to the changelog in the latest commit
        return !hasNonChangelogChanges
        
    } catch (Exception e) {
        echo "Error checking for changelog-only commit: ${e.getMessage()}"
        echo "Proceeding with full build to be safe"
        return false
    }
}