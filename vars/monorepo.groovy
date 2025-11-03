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
    def jobDslResult = jobDsl(
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
    if (jobDslResult) {
        echo "Job DSL created/updated ${jobDslResult.size()} item(s):"
        jobDslResult.each { item ->
            echo "  - ${item.fullName}"
        }
    }
    
    return jenkinsfilePaths
}

/**
 * Get the most relevant baseline revision.
 * @return A revision.
 */
String getBaselineRevision() {
    echo "Determining baseline revision for change detection..."
    
    def baseline
    
    // For PR builds, compare against the target branch
    if (env.CHANGE_TARGET) {
        echo "This is a PR build, comparing against target: ${env.CHANGE_TARGET}"
        baseline = "origin/${env.CHANGE_TARGET}"
    } else {
        // For regular branch builds, use previous commits
        baseline = [env.GIT_PREVIOUS_SUCCESSFUL_COMMIT, env.GIT_PREVIOUS_COMMIT]
                .find { revision ->
                    revision != null && sh(script: "git rev-parse --quiet --verify $revision", returnStatus: true) == 0
                } ?: 'HEAD^'
    }
    
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
            
            // The multibranch pipeline should already exist (verified in main flow)
            def multibranchPipelinePath = "${rootFolderPath}/${multibranchPipelineToRun}"
            
            // Trigger a scan of the multibranch pipeline to ensure it discovers the current branch
            echo "Triggering scan for multibranch pipeline: ${multibranchPipelinePath}"
            
            try {
                build(job: multibranchPipelinePath + '/branch-indexing', wait: true, propagate: false)
                echo "Branch indexing completed for ${multibranchPipelinePath}"
            } catch (Exception e) {
                echo "Branch indexing failed or not available: ${e.message}"
                // Continue anyway - the pipeline might already have the branch indexed
            }
            
            // For new branches, Jenkins will receive an event from the version control system to provision the
            // corresponding Pipeline under the Multibranch Pipeline item. We have to wait for Jenkins to process the
            // event so a build can be triggered.
            echo "Waiting for specific branch pipeline to become available: ${pipelineName}"
            timeout(time: 3, unit: 'MINUTES') {
                waitUntil(initialRecurrencePeriod: 2000) {
                    def pipeline = Jenkins.instance.getItemByFullName(pipelineName)
                    if (pipeline && !pipeline.isDisabled()) {
                        echo "Pipeline ${pipelineName} is ready"
                        return true
                    } else {
                        echo "Pipeline ${pipelineName} not ready yet, waiting..."
                        return false
                    }
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
    
    String repositoryName = env.JOB_NAME.split('/')[0]
    String rootFolderPath = "Generated/$repositoryName"
    List<String> jenkinsfilePaths = config.jenkinsfiles ?: []

    echo "Found ${jenkinsfilePaths.size()} Jenkinsfile(s)"
    echo "Repository Name: ${repositoryName}"
    echo "Root Folder Path: ${rootFolderPath}"

    provisionItems(rootFolderPath, env.GIT_URL, jenkinsfilePaths)
    jenkinsfilePaths.each { path ->
        echo "  - ${path}"
    }

    echo "Verifying that Job DSL items were created successfully..."
    // Wait for all expected multibranch pipelines to be created
    def expectedPipelines = jenkinsfilePaths.collect { path ->
        def jenkinsfileDir = path.replaceAll('/Jenkinsfile$', '')
        return "${rootFolderPath}/${jenkinsfileDir}"
    }
    
    expectedPipelines.each { expectedPipeline ->
        echo "Waiting for multibranch pipeline: ${expectedPipeline}"
        timeout(time: 2, unit: 'MINUTES') {
            waitUntil(initialRecurrencePeriod: 1000) {
                def pipeline = Jenkins.instance.getItemByFullName(expectedPipeline)
                if (pipeline) {
                    echo "✓ Multibranch pipeline created: ${expectedPipeline}"
                    return true
                } else {
                    echo "⏳ Still waiting for: ${expectedPipeline}"
                    return false
                }
            }
        }
    }

    echo "Step 2: Detecting Changes"
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
