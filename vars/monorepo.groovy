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
 * The step entry point.
 */
def call(Map config = [:]){
    println "Provisioning Multibranch Pipelines for Libraries..."
    
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
}
