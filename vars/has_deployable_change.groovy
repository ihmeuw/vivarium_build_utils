def call() {
    // Define the patterns to exclude (files that don't count as deployable changes)
    def excludePatterns = [
        'Jenkinsfile',
        'Makefile',
        '\.gitignore',
        '\.github/.*'
    ]
    
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
    
    // Get all changed files between the two commits
    def changedFiles = sh(
        script: "git diff --name-only ${previousCommit} ${currentCommit} || echo ''",
        returnStdout: true
    ).trim()
    
    // If no files changed, return false
    if (changedFiles == '') {
        echo "No files were changed in this commit"
        return false
    }
    
    // Join the patterns with pipe character for grep -E
    def grepPattern = excludePatterns.join('|')
    
    // Count the number of changed files that don't match the exclude patterns
    def deployableChangeCount = sh(
        script: """
            git diff --name-only ${previousCommit} ${currentCommit} |
            grep -v -E '${grepPattern}' |
            wc -l || echo '0'
        """,
        returnStdout: true
    ).trim().toInteger()
    
    // If there are deployable changes, return true
    if (deployableChangeCount > 0) {
        echo "Found ${deployableChangeCount} deployable changes"
        return true
    } else {
        echo "No deployable changes found (only excluded files were modified)"
        return false
    }
}