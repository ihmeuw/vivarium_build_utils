// build_stages.groovy

// Run debug information stage
def run_debug_info(Map config) {
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

// Build environment stage
def build_environment(Map config) {
    // The env should have been cleaned out after the last build, but delete it again
    // here just to be safe.
    sh "rm -rf ${config.CONDA_ENV_PATH}"
    sh "${env.ACTIVATE_BASE} && make create-env PYTHON_VERSION=${config.PYTHON_VERSION}"
    // open permissions for test users to create file in workspace
    sh "chmod 777 ${WORKSPACE}"
}

// Install package stage
def install_package(Map config) {
    sh "${config.ACTIVATE} && make install && pip install ."
}

// Install upstream dependency branches
def install_dependencies(Map config, List upstream_repos) {
    sh "chmod +x install_dependency_branch.sh"
    upstream_repos.each { repo ->
        sh "${config.ACTIVATE} && ./install_dependency_branch.sh ${repo} ${GIT_BRANCH} jenkins"
    }
}

// Check formatting stage
def check_formatting(Map config) {
    sh "${config.ACTIVATE} && make lint"
}

// Run tests stage
def run_tests(Map config, List test_types) {
    script {
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
        parallel parallelTests
    }
}

// Build and test docs
def handle_docs(Map config, boolean skip_doc_build) {
    if (skip_doc_build != true) {
        stage("Build Docs - Python ${config.PYTHON_VERSION}") {
            sh "${config.ACTIVATE} && make build-doc"
        }
        stage("Test Docs - Python ${config.PYTHON_VERSION}") {
            sh "${config.ACTIVATE} && make test-doc"
        }
    }
}

// Handle deployment
def handle_deployment(Map config, boolean deployable, boolean skip_doc_build) {
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

// Clean up resources
def cleanup(String conda_env_path) {
    sh "${env.ACTIVATE} && make clean"
    sh "rm -rf ${conda_env_path}"
    cleanWs()
    dir("${WORKSPACE}@tmp") {
        deleteDir()
    }
}

return this