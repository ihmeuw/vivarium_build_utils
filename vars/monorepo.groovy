/**
 * The step entry point. Provisions per-package Multibranch Pipeline jobs on Jenkins
 * for every Jenkinsfile path supplied.
 *
 * Must be called from the top-level monorepo Jenkinsfile (not a per-package pipeline).
 * The provisioner pipeline's JOB_NAME shape varies by Jenkins folder layout (e.g.
 * whether it sits inside a Jenkins Organization Folder); provisioned per-package
 * pipelines always use "<prefix>/<repo>/libs/<pkg>/<branch>".
 *
 * @param config.jenkinsfiles          (required) List of Jenkinsfile paths to provision
 *                                     (e.g. ["libs/core/Jenkinsfile"])
 * @param config.githubCredentialsId   (required) Jenkins credential ID for the GitHub App
 *                                     used by the branch source. Lives in the calling
 *                                     monorepo's Jenkinsfile so vbu stays org-agnostic.
 * @param config.folderPrefix          Jenkins folder prefix for provisioned pipelines
 *                                     (default: "Public")
 * @param config.allowedBranch         Branch this step is allowed to run from. Other
 *                                     branches no-op (echo + return). Defaults to "main"
 *                                     because removedJobAction='DELETE' below is only
 *                                     safe from the default branch — feature-branch
 *                                     builds racing to delete/recreate items would
 *                                     thrash the provisioned tree. Pass an explicit
 *                                     null to disable the guard.
 */
def call(Map config = [:]) {
    echo "Provisioning Multibranch Pipelines for Libraries..."

    // Branch guard: refuse to run from anywhere other than the default branch.
    // The reason lives next to removedJobAction='DELETE' below; in short, allowing
    // this step to run from feature branches lets concurrent builds delete each
    // other's sibling pipelines. Default-on is the safer behavior; the caller can
    // explicitly pass `allowedBranch: null` to opt out.
    String allowedBranch = config.containsKey('allowedBranch') ? config.allowedBranch : 'main'
    if (allowedBranch && env.BRANCH_NAME != allowedBranch) {
        echo "monorepo(): skipping provisioning (BRANCH_NAME='${env.BRANCH_NAME}', expected '${allowedBranch}')"
        return
    }

    List<String> jenkinsfilePaths = config.jenkinsfiles ?: []
    if (jenkinsfilePaths.isEmpty()) {
        echo "No Jenkinsfile paths provided - skipping provisioning"
        return
    }

    String githubCredentialsId = config.githubCredentialsId
    if (!githubCredentialsId) {
        error("monorepo(): 'githubCredentialsId' is required. Pass the Jenkins credential ID for the GitHub App from the calling Jenkinsfile.")
    }

    // Use GIT_URL rather than JOB_NAME to derive the repository name reliably;
    // JOB_NAME format for the provisioner pipeline varies depending on whether it
    // lives inside a Jenkins Organization Folder.
    def gitUrlMatch = env.GIT_URL =~ /.+[\/:][^\/]+\/(?<repository>[^\/]+?)(\.git)?$/
    String repositoryName = gitUrlMatch ? gitUrlMatch.group('repository') : env.GIT_URL.tokenize('/').last().replace('.git', '')
    String folderPrefix = config.folderPrefix ?: 'Public'
    String rootFolderPath = "${folderPrefix}/${repositoryName}"

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
