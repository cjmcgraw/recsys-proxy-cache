#!groovy
@Library('pipeline@3.x') _

String GCLOUD_PROJECT = 'icf-compliance'
String TEAM_EMAIL = 'recommendations@accretivetg.com'
String PROJECT_NAME = "recsysproxycachedaemon"

// globally used to capture image during build
String DOCKER_RELEASE_IMAGE_TAG

def boolean shouldDeploy() {
    if (params.FORCE_DEPLOY) {
        return true
    }
    return env.BRANCH_NAME == "master"
}

pipeline {
    agent { label 'docker' }

    environment {
        COMPOSE_DOCKER_CLI_BUILD = "1"
        DOCKER_BUILDKIT = "1"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        ansiColor('xterm')
    }

    stages {

        stage('build') {
            steps {
                script {
                    sh 'docker-compose build --no-cache --pull'
                }
            }
        }

        stage('run tests') {
            options {
                lock("${PROJECT_NAME}DockerLock")
            }

            steps {
                script {
                        sh 'docker-compose build --no-cache'
                        sh 'docker-compose build --no-cache tests'
                        sh 'docker-compose run tests'
                }
            }
            post {
                failure {
                    sh 'docker-compose logs recsys-proxy-cache'
                    sh 'docker-compose logs recsys-1'
                }

                cleanup {
                    sh 'docker-compose ps'
                    sh 'docker-compose rm --force --stop -v'
                    sh 'docker-compose ps'
                }
            }
        }

        stage("build and push images") {
            steps {
                script {
                    // atg custom specific build
                    (image) = buildDockerImage(
                        dockerContext: "./recsys-proxy-cache/java",
                        target: 'release',
                        parameters: [
                            '--pull',
                        ]
                    )

                    // atg custom specific push
                    pushToDockerRegistry(
                        dockerImage: image
                    )
                    DOCKER_RELEASE_IMAGE_TAG = image
                }
            }
        }

        stage('deploy to integ') {
            when { expression { shouldDeploy() }}
            steps {
                script {
                    echo 'Deploy to Integ Swarm'
                }
            }
        }

        stage('deploy to staging') {
            when { expression { shouldDeploy() }}
            steps {
                script {
                    echo 'Deploy to staging'
                }
            }
        }

        stage('deploy to production') {
            when { expression { shouldDeploy() }}
            steps {
                script {
                    echo 'Deploy to production'
                }
            }
        }
    }

    post {
        cleanup {
            cleanDockerImages()
            cleanWs()
        }


        failure {
            script {
                String JOB_NAME = getJobName()

                // If this is not a daily build, the failure is a general build failure
                String status = "FAILED: Job ${JOB_NAME} [${currentBuild.externalizableId}]"
                String sendTo = ''
                String message = """
                    <p>FAILED: Job ${JOB_NAME} [${currentBuild.externalizableId}] has failed its build</p>
                    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${JOB_NAME} [${currentBuild.externalizableId}]</a>&QUOT;</p>
                """.stripIndent()
                emailext(
                    subject: status,
                    mimeType: "text/html",
                    body: message,
                    to: sendTo,
                    recipientProviders: [[$class: 'DevelopersRecipientProvider']]

                )
            }
        }
    }
}
