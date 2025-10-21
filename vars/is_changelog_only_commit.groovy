def call() {
    // Check if the latest commit only contains changelog changes
    def changedFiles = sh(
        script: "git diff --name-only HEAD~1 HEAD || echo ''",
        returnStdout: true
    ).trim()
    
    // If no files are found (which shouldn't happen), return false
    if (changedFiles == '') {
        return false
    }

    // Check if all changed files in the latest commit are CHANGELOG files
    def hasNonChangelogChanges = sh(
        script: """
            git diff --name-only HEAD~1 HEAD |
            grep -v '^CHANGELOG' |
            wc -l || echo '0'
        """,
        returnStdout: true
    ).trim().toInteger() > 0
    
    // Return true if there are no non-changelog changes in the latest commit
    return !hasNonChangelogChanges
}