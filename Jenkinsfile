#!groovy
@Library('pipeline@3.x') _

String GCLOUD_PROJECT = 'icf-compliance'
String TEAM_EMAIL = 'recommendations@accretivetg.com'
String PROJECT_NAME = "recsysproxycache.daemon"

// globally used to capture image during build
String DOCKER_RELEASE_IMAGE_TAG
String DOCKER_VM_TEMPLATE_FILE_PATH = './docker-compose-deploy-vm.yml'

class ElasticsearchClusters {
    final static String INTEG_MAIN = "elasticsearch_search_integ"
    final static String STAGING_MAIN = "elasticsearch_search_staging"
    final static String STAGING_BACKUP = "elasticsearch_search_staging2"
    final static String PRODUCTION_MAIN = "elasticsearch_search_production"
    final static String PRODUCTION_BACKUP = "elasticsearch_search_production2"
}

def boolean shouldDeploy() {
    return true
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
                        dockerFile: "./recsys-proxy-cache/java/Dockerfile",
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
            environment {
                DOCKER_RELEASE_IMAGE_TAG = "${DOCKER_RELEASE_IMAGE_TAG}"
                RECSYS_TARGET="recommender.integ.icfsys.com:8500"
                RECSYS_DEADLINE="5000"
                JAVA_OPTS="-XX:+UseShenandoahGC -Xlog:gc+stats -XX:+AlwaysPreTouch -Xlog:async -XX:+UseTransparentHugePages -XX:+UseNUMA -XX:-UseBiasedLocking -XX:+DisableExplicitGC -Xms512M -Xmx512M"
                CPU_LIMIT=3
                MEMORY_LIMIT="512M"
                CPU_RESERVED=1
                MEMORY_RESERVED="512M"
            }
            steps {
                script {
                    echo 'Deploy to Integ cluster'
                }

                echo "Replacing env variables inside ${DOCKER_VM_TEMPLATE_FILE_PATH}"
                sh "envsubst < ${DOCKER_VM_TEMPLATE_FILE_PATH} > tmp.yml && mv tmp.yml ${DOCKER_VM_TEMPLATE_FILE_PATH}"

                pushToTarpit(
                    filesToCopy: [
                            "./doggy-hooks",
                            "./docker-compose-deploy.yml"
                    ],
                    osVersionOverride: 'any',
                    doggyPackage: PROJECT_NAME,
                )
                doggyInstall(
                    systemClass: ElasticsearchClusters.INTEG_MAIN,
                    doggyPackage: PROJECT_NAME
                )

            }
        }

        stage('deploy to staging') {
            when { expression { shouldDeploy() }}
            environment {
                DOCKER_RELEASE_IMAGE_TAG = "${DOCKER_RELEASE_IMAGE_TAG}"
                RECSYS_TARGET="recommender.staging.icfsys.com:8500"
                RECSYS_DEADLINE="5000"
                JAVA_OPTS="-XX:+UseShenandoahGC -Xlog:gc+stats -XX:+AlwaysPreTouch -Xlog:async -XX:+UseTransparentHugePages -XX:+UseNUMA -XX:-UseBiasedLocking -XX:+DisableExplicitGC -Xms512M -Xmx512M"
            }
            steps {
                script {
                    echo 'Deploy to staging'
                }
            }
        }

        stage('deploy to production') {
            when { expression { shouldDeploy() }}
            environment {
                DOCKER_RELEASE_IMAGE_TAG = "${DOCKER_RELEASE_IMAGE_TAG}"
                RECSYS_TARGET="recommender.icfsys.com:8500"
                RECSYS_DEADLINE="10"
                JAVA_OPTS="-XX:+UseShenandoahGC -Xlog:gc+stats -XX:+AlwaysPreTouch -Xlog:async -XX:+UseTransparentHugePages -XX:+UseNUMA -XX:-UseBiasedLocking -XX:+DisableExplicitGC -Xms6G -Xmx6G -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
            }
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
