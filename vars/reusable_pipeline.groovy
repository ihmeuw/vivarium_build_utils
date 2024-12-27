def call(Map config = [:]){
  /* This is the funtion called from the repo
  Example: fhs_standard_pipeline(job_name: JOB_NAME)
  JOB_NAME is a reserved Jenkins var
  */
  test_types = config.test_types ?: []
  // raise an error if test_types is not a subset of  ['e2e', 'unit', 'integration']
  if (!test_types.every { ['e2e', 'unit', 'integration'].contains(it) }) {
    throw new IllegalArgumentException("test_types must be a subset of ['e2e', 'unit', 'integration']")
  }
  def full_name(test_type) {
    if (test_type == 'e2e') {
      return "End-to-End"
    } else {
      return test_type.capitalize()
    }
  }

  scheduled_branches = config.scheduled_branches ?: []
  CRON_SETTINGS = scheduled_branches.contains(BRANCH_NAME) ? 'H H(20-23) * * *' : ''
  pipeline {
    // This agent runs as svc-simsci on node simsci-ci-coordinator-01.
    // It has access to standard IHME filesystems and singularity
    agent { label "coordinator" }

    options {
      // Keep 100 old builds.
      buildDiscarder logRotator(numToKeepStr: "100")
      
      // Wait 60 seconds before starting the build.
      // If another commit enters the build queue in this time, the first build will be discarded.
      quietPeriod(60)

      // Fail immediately if any part of a parallel stage fails
      parallelsAlwaysFailFast()
    }

    parameters {
      booleanParam(
        name: "DEPLOY_OVERRIDE",
        defaultValue: false,
        description: "Whether to deploy despite building a non-default branch. Builds of the default branch are always deployed."
      )
      booleanParam(
        name: "IS_CRON",
        defaultValue: true,
        description: "Indicates a recurring build. Used to skip deployment steps."
      )
      string(
        name: "SLACK_TO",
        defaultValue: "simsci-ci-status",
        description: "The Slack channel to send messages to."
      )
      booleanParam(
      name: "DEBUG",
      defaultValue: false,
      description: "Used as needed for debugging purposes."
      )
    }
    triggers {
      cron(CRON_SETTINGS)
    }

    stages {
      stage("Initialization") {
        steps {
          script {
            // Use the name of the branch in the build name
            currentBuild.displayName = "#${BUILD_NUMBER} ${GIT_BRANCH}"
          }
        }
      }

      stage("Python version matrix") {
        matrix {
          // customWorkspace setting must be ran within a node
          agent {
            node {
              label "matrix-tasks"
            }
          }
          axes {
            axis {
              // parallelize by python minor version
              name 'PYTHON_VERSION'
              values "3.10", "3.11"
            }
          }

          environment {
            // pipeline_name=${config.pipeline_name}
            conda_env_name="${env.JOB_NAME}-${BUILD_NUMBER}-${PYTHON_VERSION}"
            conda_env_path="/tmp/${conda_env_name}"
            // defaults for conda and pip are a local directory /svc-simsci for improved speed.
            // In the past, we used /ihme/code/* on the NFS (which is slower)
            shared_path="/svc-simsci"
            // Get the branch being built and strip everything but the text after the last "/"
            BRANCH = sh(script: "echo ${GIT_BRANCH} | rev | cut -d '/' -f1 | rev", returnStdout: true).trim()
            TIMESTAMP = sh(script: 'date', returnStdout: true)
            // Specify the path to the .condarc file via environment variable.
            // This file configures the shared conda package cache.
            CONDARC = "${shared_path}/miniconda3/.condarc"
            CONDA_BIN_PATH = "${shared_path}/miniconda3/bin"
            // Specify conda env by build number so that we don't have collisions if builds from
            // different branches happen concurrently.
            PYTHON_DEPLOY_VERSION = "3.11"
            CONDA_ENV_NAME = "${conda_env_name}"
            CONDA_ENV_PATH = "${conda_env_path}"
            // Set the Pip cache.
            XDG_CACHE_HOME = "${shared_path}/pip-cache"
            // Jenkins commands run in separate processes, so need to activate the environment every
            // time we run pip, poetry, etc.
            ACTIVATE = "source ${CONDA_BIN_PATH}/activate ${CONDA_ENV_PATH} &> /dev/null"
          }

          stages {

            stage("Debug Info") {
              steps {
                echo "Jenkins pipeline run timestamp: ${TIMESTAMP}"
                // Display parameters used.
                echo """Parameters:
                DEPLOY_OVERRIDE: ${params.DEPLOY_OVERRIDE}"""

                // Display environment variables from Jenkins.
                echo """Environment:
                ACTIVATE:       '${ACTIVATE}'
                BUILD_NUMBER:   '${BUILD_NUMBER}'
                BRANCH:         '${BRANCH}'
                CONDARC:        '${CONDARC}'
                CONDA_BIN_PATH: '${CONDA_BIN_PATH}'
                CONDA_ENV_NAME: '${CONDA_ENV_NAME}'
                CONDA_ENV_PATH: '${CONDA_ENV_PATH}'
                GIT_BRANCH:     '${GIT_BRANCH}'
                JOB_NAME:       '${JOB_NAME}'
                WORKSPACE:      '${WORKSPACE}'
                XDG_CACHE_HOME: '${XDG_CACHE_HOME}'"""
              }
            }

            stage("Build Environment") {
              environment {
                // Command for activating the base environment. Activating the base environment sets
                // the correct path to the conda binary which is used to create a new conda env.
                ACTIVATE_BASE = "source ${CONDA_BIN_PATH}/activate &> /dev/null"
              }
              steps {
                // The env should have been cleaned out after the last build, but delete it again
                // here just to be safe.
                sh "rm -rf ${CONDA_ENV_PATH}"
                sh "${ACTIVATE_BASE} && make build-env PYTHON_VERSION=${PYTHON_VERSION}"
                // open permissions for test users to create file in workspace
                sh "chmod 777 ${WORKSPACE}"
              }
            }

            stage("Install Package") {
              steps {
                sh "${ACTIVATE} && make install"
              }
            }

            stage("Format") {
              steps {
                sh "${ACTIVATE} && make format"
              }
            }

            stage("Run Tests") {
              steps {
                script {
                    def parallelStages = test_types.collectEntries {
                        ["${full_name(it)} Tests" : {
                            stage("Running ${full_name(it)} Tests") {
                                sh "${ACTIVATE} && make ${it}"
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
                    parallel parallelStages
                }
              }
            }

            stage('Build and Deploy') {
              when {
                expression { "${PYTHON_DEPLOY_VERSION}" == "${PYTHON_VERSION}" }
              }
              stages {
                stage("Build Docs") {
                  steps {
                    sh "${ACTIVATE} && make build-doc"
                  }
                }
                stage("Build Package") {
                  steps {
                    sh "${ACTIVATE} && make build-package"
                  }
                }
              }
            }
          } // stages within python version matrix

          post {
            always {
              // Generate a message to send to Slack.
              script {
                // Run git command to get the author of the last commit
                developerID = sh(
                  script: "git log -1 --pretty=format:'%an'",
                  returnStdout: true
                ).trim()
                echo "Most recent developerID: ${developerID}"
                if (env.BRANCH == "main") {
                  channelName = "simsci-ci-status"
                  slackID = "channel"
                } else {
                  channelName = "simsci-ci-status-test"
                  slackID = github_slack_mapper(github_author: developerID)
                }
                echo "slackID to tag in slack message: ${slackID}"
                slackMessage = """
                  Job: *${env.JOB_NAME}*
                  Build number: #${env.BUILD_NUMBER}
                  Build status: *${currentBuild.result}*
                  Author: @${slackID}
                  Build details: <${env.BUILD_URL}/console|See in web console>
                  """.stripIndent()
              }
            }
            failure {
              echo "This build triggered by ${developerID} failed on ${GIT_BRANCH}. Sending a failure message to Slack."
              slackSend channel: "#${channelName}",
                        message: slackMessage,
                        teamDomain: "ihme",
                        tokenCredentialId: "slack"
            }
            success {
              script {
                if (params.DEBUG) {
                  echo 'Debug is enabled. Sending a success message to Slack.'
                  slackSend channel: "#${channelName}",
                            message: slackMessage,
                            teamDomain: "ihme",
                            tokenCredentialId: "slack"
                } else {
                  echo 'Debug is not enabled. No success message will be sent to Slack.'
                }
              }
            }
            cleanup {  // cleanup for python matrix workspaces
              sh "${ACTIVATE} && make clean"
              sh "rm -rf ${CONDA_ENV_PATH}"
              cleanWs()
              // manually remove @tmp dirs
              dir("${WORKSPACE}@tmp"){
                deleteDir()
              }
            }
          }  // End of post stage
        }  // End of python version matrix
      }  // End of python version matrix stage
    }  // End of stages
    post {
      cleanup { // cleanup for outer workspace
        cleanWs()
        // manually remove @tmp dirs
        dir("${WORKSPACE}@tmp"){
          deleteDir()
        }
      }
    }
  }  // End of pipeline
}
