def call() {
    // Get the previous commit hash
    def previousCommit = sh(
        script: "git rev-parse HEAD~1",
        returnStdout: true
    ).trim()
    
    // Get the current commit hash
    def currentCommit = sh(
        script: "git rev-parse HEAD",
        returnStdout: true
    ).trim()
    
    // Check if CHANGELOG.rst was modified between the two commits
    def changelogModified = sh(
        script: "git diff --name-only ${previousCommit} ${currentCommit} | grep -q 'CHANGELOG.rst' && echo 'true' || echo 'false'",
        returnStdout: true
    ).trim()
    
    if (changelogModified == 'true') {
        // Get the diff of the CHANGELOG.rst
        def changelogDiff = sh(
            script: "git diff ${previousCommit} ${currentCommit} CHANGELOG.rst",
            returnStdout: true
        ).trim()
        
        // Define a regex pattern to match version format X.X.X
        def versionPattern = /\+.*?(\d+\.\d+\.\d+/
        
        // Check if the pattern exists in the changelog diff
        def matcher = changelogDiff =~ versionPattern
        
        if (matcher.find()) {
            echo "Found version update in CHANGELOG.rst: ${matcher.group(1)}"
            return true
        } else {
            echo "ERROR: No version update found in CHANGELOG.rst with format X.X.X"
            return false
        }
    } else {
        echo "ERROR: CHANGELOG.rst was not modified in this commit"
        return false
    }
}