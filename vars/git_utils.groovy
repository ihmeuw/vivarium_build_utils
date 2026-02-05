def getPreviousCommit() {
    return sh(
        script: "git rev-parse HEAD~1",
        returnStdout: true
    ).trim()
}

def getCurrentCommit() {
    return env.GIT_COMMIT ?: sh(
        script: "git rev-parse HEAD",
        returnStdout: true
    ).trim()
}

def getChangedFiles(previousCommit, currentCommit) {
    return sh(
        script: "git diff --name-only ${previousCommit} ${currentCommit} || echo ''",
        returnStdout: true
    ).trim()
}

// Combined function to get both commits and changed files
def getCommitInfo() {
    def previousCommit = getPreviousCommit()
    def currentCommit = getCurrentCommit()
    def changedFiles = getChangedFiles(previousCommit, currentCommit)
    
    return [
        previousCommit: previousCommit,
        currentCommit: currentCommit,
        changedFiles: changedFiles
    ]
}

/**
 * Get the commit SHA from the previous Jenkins build.
 * 
 * This checks multiple sources in order:
 * 1. GIT_PREVIOUS_COMMIT environment variable (set by Jenkins Git plugin)
 * 2. Previous build's GIT_COMMIT from build variables
 * 
 * Returns null if no previous build exists or commit cannot be determined.
 */
def getPreviousBuildCommit() {
    // First try the Jenkins Git plugin environment variable
    if (env.GIT_PREVIOUS_COMMIT) {
        echo "Using GIT_PREVIOUS_COMMIT: ${env.GIT_PREVIOUS_COMMIT}"
        return env.GIT_PREVIOUS_COMMIT
    }
    
    // Fall back to accessing previous build's variables
    def previousBuild = currentBuild.previousBuild
    if (previousBuild != null) {
        try {
            def buildVars = previousBuild.getBuildVariables()
            if (buildVars['GIT_COMMIT']) {
                echo "Using previous build's GIT_COMMIT: ${buildVars['GIT_COMMIT']}"
                return buildVars['GIT_COMMIT']
            }
        } catch (Exception e) {
            echo "Could not get previous build commit: ${e.message}"
        }
    }
    
    echo "No previous build commit found"
    return null
}

/**
 * Get the list of files changed since the last Jenkins build.
 * 
 * Returns an empty string if:
 * - No previous build exists (first build)
 * - The previous commit cannot be determined
 * - Git diff fails (e.g., shallow clone without the previous commit)
 * 
 * An empty string should be treated as "run full build" by callers.
 */
def getChangedFilesSinceLastBuild() {
    def previousCommit = getPreviousBuildCommit()
    def currentCommit = getCurrentCommit()
    
    if (!previousCommit) {
        echo "No previous build commit found. Cannot determine changed files."
        return ''
    }
    
    // Check if the previous commit exists in the current clone
    def commitExists = sh(
        script: "git cat-file -e ${previousCommit} 2>/dev/null && echo 'exists' || echo 'missing'",
        returnStdout: true
    ).trim()
    
    if (commitExists == 'missing') {
        echo "Previous build commit ${previousCommit} not found in current clone (possibly shallow clone). Cannot determine changed files."
        return ''
    }
    
    def changedFiles = sh(
        script: "git diff --name-only ${previousCommit} ${currentCommit} 2>/dev/null || echo ''",
        returnStdout: true
    ).trim()
    
    return changedFiles
}