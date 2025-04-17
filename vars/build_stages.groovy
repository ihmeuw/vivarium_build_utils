def call() {
    // Return a map of functions that can be accessed
    return [
        runDebugInfo: this.&runDebugInfo,
        buildEnvironment: this.&buildEnvironment,
        installPackage: this.&installPackage,
        installDependencies: this.&installDependencies,
        checkFormatting: this.&checkFormatting,
        runTests: this.&runTests,
        handleDocs: this.&handleDocs,
        handleDeployment: this.&handleDeployment,
        cleanup: this.&cleanup
    ]
}

// Individual function implementations
def runDebugInfo(Map config) {
    echo "Jenkins pipeline run timestamp: ${env.TIMESTAMP}"
    // Display parameters used.
    echo """Parameters:
    SKIP_DEPLOY: ${params.SKIP_DEPLOY}
    RUN_SLOW: ${params.RUN_SLOW}
    SLACK_TO: ${params.SLACK_TO}
    DEBUG: ${params.DEBUG}"""

    // Display environment variables from Jenkins.
    echo """Environment:
    ACTIVATE:       '${config.ACTIVATE}'
    BUILD_NUMBER:   '${BUILD_NUMBER}'
    BRANCH:         '${env.BRANCH}'
    CONDARC:        '${env.CONDARC}'
    CONDA_BIN_PATH: '${env.CONDA_BIN_PATH}'
    CONDA_ENV_NAME: '${config.CONDA_ENV_NAME}'
    CONDA_ENV_PATH: '${config.CONDA_ENV_PATH}'
    GIT_BRANCH:     '${GIT_BRANCH}'
    JOB_NAME:       '${env.JOB_NAME}'
    WORKSPACE:      '${WORKSPACE}'
    XDG_CACHE_HOME: '${env.XDG_CACHE_HOME}'"""
}

def buildEnvironment(Map config) {
    // The env should have been cleaned out after the last build, but delete it again
    // here just to be safe.
    sh "rm -rf ${config.CONDA_ENV_PATH}"
    sh "${env.ACTIVATE_BASE} && make create-env PYTHON_VERSION=${config.PYTHON_VERSION}"
    // open permissions for test users to create file in workspace
    sh "chmod 777 ${WORKSPACE}"
}

def installPackage(Map config) {
    sh "${config.ACTIVATE} && make install && pip install ."
}

def installDependencies(Map config, List upstream_repos) {
    sh "chmod +x install_dependency_branch.sh"
    upstream_repos.each { repo ->
        sh "${config.ACTIVATE} && ./install_dependency_branch.sh ${repo} ${GIT_BRANCH} jenkins"
    }
}

def checkFormatting(Map config) {
    sh "${config.ACTIVATE} && make lint"
}

def runTests(Map config, List test_types) {
    def full_name = { test_type ->
        if (test_type == 'e2e') {
            return "End-to-End"
        } else if (test_type == 'all-tests') {
            return "All"
        } else {
            return test_type.capitalize()
        }
    }
    def parallelTests = test_types.collectEntries {
        ["${full_name(it)} Tests" : {
            stage("Run ${full_name(it)} Tests - Python ${config.PYTHON_VERSION}") {
                sh "${config.ACTIVATE} && make ${it}${(env.IS_CRON.toBoolean() || params.RUN_SLOW) ? ' RUNSLOW=1' : ''}"
                publishHTML([
                    allowMissing: true,
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: "output/htmlcov_${it}",
                    reportFiles: "index.html",
                    reportName: "Coverage Report - ${full_name(it)} tests",
                    reportTitles: ''
                ])
            }
        }]
    }
    return parallelTests
}

def handleDocs(Map config, boolean skip_doc_build) {
    if (skip_doc_build != true) {
        stage("Build Docs - Python ${config.PYTHON_VERSION}") {
            sh "${config.ACTIVATE} && make build-doc"
        }
        stage("Test Docs - Python ${config.PYTHON_VERSION}") {
            sh "${config.ACTIVATE} && make test-doc"
        }
    }
}

def handleDeployment(Map config, boolean deployable, boolean skip_doc_build) {
    if (deployable && 
        !env.IS_CRON.toBoolean() &&
        !params.SKIP_DEPLOY &&
        (env.BRANCH == "main")) {
        
        stage("Tagging Version and Pushing") {
            sh "${config.ACTIVATE} && make tag-version"
        }

        stage("Build Package - Python ${config.PYTHON_VERSION}") {
            sh "${config.ACTIVATE} && make build-package"
        }

        stage("Deploy Package to Artifactory") {
            withCredentials([usernamePassword(
                credentialsId: 'artifactory_simsci',
                usernameVariable: 'PYPI_ARTIFACTORY_CREDENTIALS_USR',
                passwordVariable: 'PYPI_ARTIFACTORY_CREDENTIALS_PSW'
            )]) {
                sh "${config.ACTIVATE} && make deploy-package-artifactory"
            }
        }
        
        if (skip_doc_build != true) {
            stage("Deploy Docs") {
                withEnv(["DOCS_ROOT_PATH=/mnt/team/simulation_science/pub/docs"]) {
                    sh "${config.ACTIVATE} && make deploy-doc"
                }
            }
        }
    }
}

def cleanup(String conda_env_path) {
    sh "${env.ACTIVATE} && make clean"
    sh "rm -rf ${conda_env_path}"
    cleanWs()
    dir("${WORKSPACE}@tmp") {
        deleteDir()
    }
}