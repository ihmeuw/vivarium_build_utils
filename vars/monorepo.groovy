/**
 * The step entry point. Provisions per-package Multibranch Pipeline jobs on Jenkins
 * for every Jenkinsfile path supplied.
 *
 * Must be called from the top-level monorepo Jenkinsfile (not a per-package pipeline).
 * The provisioner pipeline's JOB_NAME format is "<something>/<repo>/<branch>"; the
 * per-package pipelines it creates use "<prefix>/<repo>/libs/<pkg>/<branch>".
 *
 * @param config.jenkinsfiles          List of Jenkinsfile paths to provision (e.g. ["libs/core/Jenkinsfile"])
 * @param config.folderPrefix          Jenkins folder prefix for provisioned pipelines (default: "Public")
 * @param config.githubCredentialsId   Jenkins credential ID for the GitHub App used by the
 *                                     branch source. Defaults to the ihmeuw org credential.
 */
def call(Map config = [:]) {
    echo "Provisioning Multibranch Pipelines for Libraries..."

    List<String> jenkinsfilePaths = config.jenkinsfiles ?: []
    if (jenkinsfilePaths.isEmpty()) {
        echo "No Jenkinsfile paths provided — skipping provisioning"
        return
    }

    // Use GIT_URL rather than JOB_NAME to derive the repository name reliably;
    // JOB_NAME format for the provisioner pipeline varies depending on whether it
    // lives inside a Jenkins Organization Folder.
    String repositoryName = env.GIT_URL.tokenize('/').last().replace('.git', '')
    String folderPrefix = config.folderPrefix ?: 'Public'
    String rootFolderPath = "${folderPrefix}/${repositoryName}"
    // Jenkins credential ID for the GitHub App on ihmeuw. Override if/when vbu
    // is reused across orgs or Jenkins instances with different credentials.
    String githubCredentialsId = config.githubCredentialsId ?: 'fad62062-b1f4-447b-997f-005d6b1ea41e'

    echo "Found ${jenkinsfilePaths.size()} Jenkinsfile(s)"
    echo "Repository Name: ${repositoryName}"
    echo "Root Folder Path: ${rootFolderPath}"
    jenkinsfilePaths.each { path -> echo "  - ${path}" }

    def provisionItems = { String rootPath, String repoURL, List<String> paths, String credId ->
        echo "Executing Job DSL to provision Jenkins items..."
        jobDsl(
            scriptText: libraryResource('multiPipelines.groovy'),
            additionalParameters: [
                jenkinsfilePathStrings: paths,
                rootFolderStr         : rootPath,
                repositoryURL         : repoURL,
                githubCredentialsId   : credId
            ],
            // The following may be set to 'IGNORE'. Using 'DELETE' is safe ONLY because
            // monorepo() is expected to run from the default branch (main) of the
            // top-level monorepo Jenkinsfile - not enforced here; the calling Jenkinsfile
            // must gate this step (e.g. `if (env.BRANCH_NAME == 'main')`). If 'DELETE' ran
            // from feature branches, branches would compete to delete and recreate items.
            removedJobAction: 'DELETE'
        )
        echo "Job DSL execution completed"
    }

    provisionItems(rootFolderPath, env.GIT_URL, jenkinsfilePaths, githubCredentialsId)
}
