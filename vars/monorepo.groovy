import hudson.model.*

/**
 * Provision items on Jenkins.
 * @param rootFolderPath A root folder path.
 * @param repositoryURL The repository URL.
 * @return The list of Jenkinsfile paths for which corresponding items have been provisioned.
 */
List<String> provisionItems(String rootFolderPath, String repositoryURL, List<String> jenkinsfilePaths) {
    echo "Executing Job DSL to provision Jenkins items..."
    // Provision folder and Multibranch Pipelines.
    jobDsl(
            scriptText: libraryResource('multiPipelines.groovy'),
            additionalParameters: [
                    jenkinsfilePathsStr: jenkinsfilePaths,
                    rootFolderStr      : rootFolderPath,
                    repositoryURL      : repositoryURL
            ],
            // The following may be set to 'DELETE'. Note that branches will compete to delete and recreate items
            // unless you only provision items from the default branch.
            removedJobAction: 'IGNORE'
    )
    echo "Job DSL execution completed"
}

/**
 * Get the most relevant baseline revision.
 * @return A revision.
 */
String getBaselineRevision() {
    echo "Determining baseline revision for change detection..."
    
    // Depending on your seed pipeline configuration and preferences, you can set the baseline revision to a target
    // branch, e.g. the repository's default branch or even `env.CHANGE_TARGET` if Jenkins is configured to discover
    // pull requests.
    def baseline = [env.GIT_PREVIOUS_SUCCESSFUL_COMMIT, env.GIT_PREVIOUS_COMMIT]
    // Look for the first existing existing revision. Commits can be removed (e.g. with a `git push --force`), so a
    // previous build revision may not exist anymore.
            .find { revision ->
                revision != null && sh(script: "git rev-parse --quiet --verify $revision", returnStatus: true) == 0
            } ?: 'HEAD^'
    
    echo "Using baseline revision: ${baseline}"
    return baseline
}

/**
 * Get the list of changed directories.
 * @param baselineRevision A revision to compare to the current revision.
 * @return The list of directories which include changes.
 */
List<String> getChangedDirectories(String baselineRevision) {
    echo "Getting changed directories compared to: ${baselineRevision}"
    
    // Jenkins native interface to retrieve changes, i.e. `currentBuild.changeSets`, returns an empty list for newly
    // created branches (see https://issues.jenkins.io/browse/JENKINS-14138), so let's use `git` instead.
    def result = sh(
            label: 'List changed directories',
            script: "git diff --name-only ${baselineRevision} | xargs -L1 dirname | uniq || echo ''",
            returnStdout: true,
    ).trim()
    
    return result ? result.split().toList() : []
}

/**
 * Find Multibranch Pipelines which Jenkinsfile is located in a directory that includes changes.
 * @param changedFilesPathStr List of changed files paths.
 * @param jenkinsfilePathsStr List of Jenkinsfile paths.
 * @return A list of Pipeline names, relative to the repository root.
 */
List<String> findRelevantMultibranchPipelines(List<String> changedFilesPathStr, List<String> jenkinsfilePathsStr) {
    def result = []
    
    for (String changedFilePath : changedFilesPathStr) {
        for (String jenkinsfilePath : jenkinsfilePathsStr) {
            // Extract the directory containing the Jenkinsfile
            def jenkinsfileDir = jenkinsfilePath.replaceAll('/Jenkinsfile$', '')
            
            // Check if the changed file is in or under the Jenkinsfile directory
            if (changedFilePath.startsWith(jenkinsfileDir + '/') || changedFilePath == jenkinsfileDir) {
                if (!result.contains(jenkinsfileDir)) {
                    result.add(jenkinsfileDir)
                }
            }
        }
    }
    
    return result
}

/**
 * Get all open pull requests from GitHub API.
 * @return List of open PR branch names.
 */
List<String> getOpenPullRequests() {
    try {
        // Extract repository info from GIT_URL
        def gitUrlStr = env.GIT_URL?.toString()
        if (!gitUrlStr) {
            echo "GIT_URL not available, cannot fetch PRs"
            return []
        }
        
        def matcher = gitUrlStr =~ /.+[\/:](?<owner>[^\/]+)\/(?<repository>[^\/]+)(?:\.git)?$/
        if (!matcher.matches()) {
            echo "Could not parse repository URL: ${gitUrlStr}"
            return []
        }
        String repoOwner = matcher.group('owner')
        String repoName = matcher.group('repository')
        
        // Use GitHub CLI or API to get open PRs
        def result = sh(
            script: """
                # Try using GitHub CLI first (if available)
                if command -v gh &> /dev/null; then
                    gh pr list --repo ${repoOwner}/${repoName} --state open --json headRefName --jq '.[].headRefName' || echo ''
                else
                    # Fallback: use git to list remote PR branches
                    git ls-remote --heads origin | grep -E 'refs/heads/(pr-|feature/|fix/)' | sed 's|.*refs/heads/||' || echo ''
                fi
            """,
            returnStdout: true
        )
        
        // Ensure we have a string and trim it safely
        def trimmedResult = result?.toString()?.trim() ?: ''
        
        return trimmedResult ? trimmedResult.split('\n').toList() : []
    } catch (Exception e) {
        echo "Warning: Could not fetch open PRs: ${e.message}"
        return []
    }
}

/**
 * Get changes in a specific branch compared to main.
 * @param branchName The branch to compare.
 * @param targetBranch The target branch (default: main).
 * @return List of changed directories in the branch.
 */
List<String> getChangedDirectoriesInBranch(String branchName, String targetBranch = 'main') {
    try {
        def result = sh(
            script: """
                # Fetch the target branch to ensure we have latest
                git fetch origin ${targetBranch}:${targetBranch} 2>/dev/null || true
                
                # Get changes between target branch and the specified branch
                git diff --name-only origin/${targetBranch}...origin/${branchName} | xargs -L1 dirname | uniq || echo ''
            """,
            returnStdout: true
        )
        
        // Ensure we have a string and trim it safely
        def trimmedResult = result?.toString()?.trim() ?: ''
        
        return trimmedResult ? trimmedResult.split('\n').toList() : []
    } catch (Exception e) {
        echo "Warning: Could not get changes for branch ${branchName}: ${e.message}"
        return []
    }
}

/**
 * Get all pipelines that should run for open PRs with changes.
 * @param jenkinsfilePaths The list of Jenkinsfiles paths.
 * @return Map of [branchName: List<pipelinePaths>] for PRs with relevant changes.
 */
Map<String, List<String>> findPipelinesToRunForOpenPRs(List<String> jenkinsfilePaths) {
    echo "=== Detecting Open PRs with Changes ==="
    
    List<String> openPRs = getOpenPullRequests()
    echo "Found ${openPRs.size()} open PR(s): ${openPRs}"
    
    Map<String, List<String>> prPipelines = [:]
    
    for (String prBranch : openPRs) {
        echo "Checking changes in PR branch: ${prBranch}"
        // todo this only find differences between the PR branch and main
        //   we also want to look for differences between the current state of the
        //   branch and its state on the most recent build
        List<String> changedDirs = getChangedDirectoriesInBranch(prBranch)
        List<String> relevantPipelines = findRelevantMultibranchPipelines(changedDirs, jenkinsfilePaths)
        
        if (!relevantPipelines.isEmpty()) {
            prPipelines[prBranch] = relevantPipelines
            echo "  → PR ${prBranch} affects: ${relevantPipelines}"
        } else {
            echo "  → PR ${prBranch} has no relevant changes"
        }
    }
    
    return prPipelines
}

/**
 * Get the list of Multibranch Pipelines that should be run according to the changeset.
 * @param jenkinsfilePaths The list of Jenkinsfiles paths.
 * @return The list of Multibranch Pipelines to run relative to the repository root.
 */
List<String> findMultibranchPipelinesToRun(List<String> jenkinsfilePaths) {
    String baselineRevision = getBaselineRevision()
    List<String> changedDirectories = getChangedDirectories(baselineRevision)
    return findRelevantMultibranchPipelines(changedDirectories, jenkinsfilePaths)
}

/**
 * Get the full pipeline name for a given multibranch pipeline path.
 * @param rootFolderPath The root folder path for generated pipelines.
 * @param multibranchPipelineToRun The multibranch pipeline path.
 * @return The full pipeline name including encoded branch.
 */
def getPipelineName(String rootFolderPath, String multibranchPipelineToRun) {
    // For PR builds, prefer the source branch
    def branchName
    if (env.CHANGE_BRANCH) {
        branchName = env.CHANGE_BRANCH
    } else {
        // Strip remote prefix if present
        branchName = env.GIT_BRANCH ?: 'main'
        if (branchName.contains('/')) {
            branchName = branchName.split('/', 2)[1]
        }
    }
    
    def encodedBranch = URLEncoder.encode(branchName, 'UTF-8')
    return "${rootFolderPath}/${multibranchPipelineToRun}/${encodedBranch}"
}

/**
 * Get the full pipeline name for a specific branch (used for PR builds).
 * @param rootFolderPath The root folder path for generated pipelines.
 * @param multibranchPipelineToRun The multibranch pipeline path.
 * @param branchName The specific branch name to build.
 * @return The full pipeline name including encoded branch.
 */
def getPipelineNameForBranch(String rootFolderPath, String multibranchPipelineToRun, String branchName) {
    def encodedBranch = URLEncoder.encode(branchName, 'UTF-8')
    return "${rootFolderPath}/${multibranchPipelineToRun}/${encodedBranch}"
}

/**
 * Run pipelines for specific PR branches that have changes.
 * @param rootFolderPath The common root folder of Multibranch Pipelines.
 * @param prPipelinesMap Map of [branchName: List<pipelinePaths>] from findPipelinesToRunForOpenPRs.
 */
def runPipelinesForPRs(String rootFolderPath, Map<String, List<String>> prPipelinesMap) {
    if (prPipelinesMap.isEmpty()) {
        echo "No PR pipelines to run - exiting"
        return
    }
    
    echo "Preparing to run pipelines for ${prPipelinesMap.size()} PR(s)"
    
    Map<String, Closure> parallelStages = [:]
    
    prPipelinesMap.each { branchName, pipelinePaths ->
        pipelinePaths.each { pipelinePath ->
            def stageKey = "PR ${branchName} - ${pipelinePath}"
            parallelStages[stageKey] = {
                def pipelineName = getPipelineNameForBranch(rootFolderPath, pipelinePath, branchName)
                
                echo "Triggering PR pipeline: ${pipelineName} (branch: ${branchName})"
                
                try {
                    // Wait for pipeline to be available
                    timeout(time: 5, unit: 'MINUTES') {
                        waitUntil {
                            def pipeline = Jenkins.instance.getItemByFullName(pipelineName)
                            return pipeline && !pipeline.isDisabled()
                        }
                    }
                    
                    echo "Starting build for PR pipeline: ${pipelineName}"
                    build(job: pipelineName, propagate: false, wait: false) // Non-blocking for PR builds
                    echo "Triggered build for PR pipeline: ${pipelineName}"
                } catch (Exception e) {
                    echo "Warning: Could not trigger ${pipelineName}: ${e.message}"
                }
            }
        }
    }
    
    if (!parallelStages.isEmpty()) {
        parallel(parallelStages)
        echo "All PR pipeline triggers completed"
    }
}

/**
 * Run pipelines.
 * @param rootFolderPath The common root folder of Multibranch Pipelines.
 * @param multibranchPipelinesToRun The list of Multibranch Pipelines for which a Pipeline is run.
 */
def runPipelines(String rootFolderPath, List<String> multibranchPipelinesToRun) {
    if (multibranchPipelinesToRun.isEmpty()) {
        echo "No pipelines to run - exiting"
        return
    }
    
    echo "Preparing to run ${multibranchPipelinesToRun.size()} pipeline(s) in parallel"
    
    parallel(multibranchPipelinesToRun.inject([:]) { stages, multibranchPipelineToRun ->
        stages + [("Build ${multibranchPipelineToRun}"): {
            def pipelineName = getPipelineName(rootFolderPath, multibranchPipelineToRun)
            
            echo "Triggering pipeline: ${pipelineName}"
            
            // For new branches, Jenkins will receive an event from the version control system to provision the
            // corresponding Pipeline under the Multibranch Pipeline item. We have to wait for Jenkins to process the
            // event so a build can be triggered.
            echo "Waiting for pipeline to become available..."
            timeout(time: 5, unit: 'MINUTES') {
                waitUntil {
                    def pipeline = Jenkins.instance.getItemByFullName(pipelineName)
                    if (pipeline && !pipeline.isDisabled()) {
                        echo "Pipeline ${pipelineName} is ready"
                        return true
                    }
                    return false
                }
            }

            echo "Starting build for: ${pipelineName}"
            // Trigger downstream builds.
            build(job: pipelineName, propagate: true, wait: true)
            echo "Completed build for: ${pipelineName}"
        }]
    })
    
    echo "All downstream builds completed"
}

/**
 * The step entry point.
 */
def call(Map config = [:]){
    println "Step 1: Provisioning Jenkins Items"
    
    // Safely handle JOB_NAME which might be null or not a string
    def jobNameStr = env.JOB_NAME?.toString() ?: 'unknown'
    String repositoryName = jobNameStr.contains('/') ? jobNameStr.split('/')[0] : jobNameStr
    String rootFolderPath = "Generated/$repositoryName"
    List<String> jenkinsfilePaths = config.jenkinsfiles ?: []

    echo "Found ${jenkinsfilePaths.size()} Jenkinsfile(s)"
    echo "Repository Name: ${repositoryName}"
    echo "Root Folder Path: ${rootFolderPath}"

    provisionItems(rootFolderPath, env.GIT_URL, jenkinsfilePaths)
    jenkinsfilePaths.each { path ->
        echo "  - ${path}"
    }

    
    // Check if we should scan all open PRs (useful for nightly builds or manual triggers)
    boolean scanAllPRs = config.scanAllOpenPRs ?: true
    
    if (scanAllPRs) {
        echo "Enhanced mode: Scanning all open PRs for changes"
        
        // Get PR-specific pipeline mappings
        Map<String, List<String>> prPipelinesMap = findPipelinesToRunForOpenPRs(jenkinsfilePaths)
        
        echo "Step 3: Triggering PR-Specific Builds"
        if (!prPipelinesMap.isEmpty()) {
            echo "Found ${prPipelinesMap.size()} PR(s) with changes:"
            prPipelinesMap.each { branchName, pipelines ->
                echo "  - PR ${branchName}: ${pipelines}"
            }
            runPipelinesForPRs(rootFolderPath, prPipelinesMap)
        } else {
            echo "No open PRs with relevant changes"
        }
        
    }
    echo "Step 2: Detecting Changes on main"
    
    List<String> multibranchPipelinesToRun = findMultibranchPipelinesToRun(jenkinsfilePaths)
    
    if (multibranchPipelinesToRun.isEmpty()) {
        echo "No relevant changes detected - skipping downstream builds"
        return
    }
    
    echo "Changes detected affecting ${multibranchPipelinesToRun.size()} pipeline(s):"
    multibranchPipelinesToRun.each { pipeline ->
        echo "  - ${pipeline}"
    }

    echo "Step 3: Triggering Downstream Builds"
    runPipelines(rootFolderPath, multibranchPipelinesToRun)
    
    echo "Multi-Multibranch Pipeline execution completed"
}
