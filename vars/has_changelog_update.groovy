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
        // Extract only the first version number from the previous changelog
        def previousVersion = sh(
            script: "git show ${previousCommit}:CHANGELOG.rst | grep -E '[0-9]+\.[0-9]+\.[0-9]+' | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || echo ''",
            returnStdout: true
        ).trim()
        
        // Extract only the first version number from the current changelog
        def currentVersion = sh(
            script: "git show ${currentCommit}:CHANGELOG.rst | grep -E '[0-9]+\.[0-9]+\.[0-9]+' | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || echo ''",
            returnStdout: true
        ).trim()
        
        // Check if both previous and current versions were found
        if (previousVersion == '') {
            echo "ERROR: Could not find version in previous changelog"
            return false
        }
        
        if (currentVersion == '') {
            echo "ERROR: Could not find version in current changelog"
            return false
        }
        
        // Check if the version has been updated
        if (previousVersion == currentVersion) {
            echo "ERROR: Changelog version has not been updated. Still: ${currentVersion}"
            return false
        }
        
        echo "Found version update in CHANGELOG.rst: from ${previousVersion} to ${currentVersion}"
        return true
    } else {
        echo "ERROR: CHANGELOG.rst was not modified in this commit"
        return false
    }
}