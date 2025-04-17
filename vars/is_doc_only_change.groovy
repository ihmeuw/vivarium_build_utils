def call() {
    // Check if this is a PR build by looking for CHANGE_TARGET
    echo "${env.CHANGE_TARGET}"
    if (env.CHANGE_TARGET) {
        // Get the list of changed files
        def changedFiles = sh(
            script: "git diff --name-only origin/${env.CHANGE_TARGET} || echo ''",
            returnStdout: true
        ).trim()
        echo "Changed files: ${changedFiles}"
        // If no files are found (which shouldn't happen), return false
        if (changedFiles == '') {
            return false
        }

        // Check if all changed files are within the docs/ directory
        def hasNonDocChanges = sh(
            script: """
                git diff --name-only origin/${env.CHANGE_TARGET} |
                grep -v '^docs/' |
                wc -l || echo '0'
            """,
            returnStdout: true
        ).trim().toInteger() > 0
        echo "Has non-doc changes: ${hasNonDocChanges}"
        // Return true if there are no non-doc changes
        return !hasNonDocChanges
    } else {
        // Not a PR build, so return false
        return false
    }
}
