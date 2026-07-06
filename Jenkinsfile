pipeline {
  agent any

  triggers {
    githubPush()
  }

  environment {
    MAVEN_REPO = "/var/lib/jenkins/.m2/repository"
    DEPLOY_PATH = "/home/ubuntu/jar/jenkins/deploy"
    REMOTE_IP = "168.231.102.240"
    APP_NAME = "royawl-api"
    IMAGE_NAME = "royawl-api:latest"
    IMAGE_TAR = "royawl-api.tar"
  }

  stages {
    stage('Production Guard') {
      when {
        anyOf {
          branch 'master'
          expression { env.GIT_BRANCH == 'origin/master' }
          triggeredBy 'UserIdCause'
        }
      }
      stages {
        stage('Prepare') {
          steps {
            checkout scm
          }
        }

        stage('Build') {
          steps {
            dir('common-service') {
              git branch: 'main',
              url: 'https://github.com/major-NEELKAMAL/royawl-common-services.git',
              credentialsId: 'github-token'
              sh 'mvn clean install -DskipTests -Dmaven.repo.local=$MAVEN_REPO'
            }
            dir('.') {
              sh 'mvn clean package -DskipTests -Dmaven.repo.local=$MAVEN_REPO'
            }
          }
        }

        stage('Docker Image Create') {
          steps {
            script {

              sh "docker build -t ${IMAGE_NAME} ."

              sh "docker save ${IMAGE_NAME} -o ${IMAGE_TAR}"
            }
          }
        }

        stage('Upload Docker Image') {
          steps {
            withCredentials([
              usernamePassword(credentialsId: 'vps-root', usernameVariable: 'USER', passwordVariable: 'PASS')
            ]) {

              sh "sshpass -p '$PASS' scp -o StrictHostKeyChecking=no ${IMAGE_TAR} ${USER}@${REMOTE_IP}:${DEPLOY_PATH}/${IMAGE_TAR}"
            }
          }
        }

        stage('Database Migration - Upload changelog') {
          steps {
            withCredentials([
              usernamePassword(credentialsId: 'vps-root', usernameVariable: 'VPS_USER', passwordVariable: 'VPS_PASS'),
            ]) {
              script {
                def changelogLocalPath = "${WORKSPACE}/common-service/src/main/resources/db/changelog/changelog.sql"
                sh "sshpass -p '$VPS_PASS' ssh -o StrictHostKeyChecking=no ${VPS_USER}@${REMOTE_IP} 'rm -f ${DEPLOY_PATH}/changelog.sql'"
                sh "sshpass -p '$VPS_PASS' scp -o StrictHostKeyChecking=no ${changelogLocalPath} ${VPS_USER}@${REMOTE_IP}:${DEPLOY_PATH}/changelog.sql"
              }
            }
          }
        }

        stage('Cleanup Docker & Local Artifacts') {
          steps {
            script {
              try {

                sh "docker rmi -f ${IMAGE_NAME}"


                sh "docker container prune -f"
                sh "docker image prune -f"
                sh "docker builder prune -f"

                sh "rm -f ${IMAGE_TAR}"
              } catch (e) {
                echo "Cleanup failed or nothing to clean, ignoring: ${e}"
              }
            }
          }
        }        

        stage('Database Migration - Run Liquibase') {
          steps {
            withCredentials([
              usernamePassword(credentialsId: 'vps-root', usernameVariable: 'VPS_USER', passwordVariable: 'VPS_PASS'),
              usernamePassword(credentialsId: 'mysql', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')
            ]) {
              sh """
            sshpass -p '$VPS_PASS' ssh -o StrictHostKeyChecking=no ${VPS_USER}@${REMOTE_IP} << 'EOF'
                cd ${DEPLOY_PATH}

                echo "--- Liquibase: Checking pending changes (Dry Run) ---"
                liquibase --driver=com.mysql.cj.jdbc.Driver \
                    --changelog-file=changelog.sql \
                    --url="jdbc:mysql://localhost:3306/royawl" \
                    --username=${DB_USER} \
                    --password=${DB_PASS} \
                    updateSQL

                echo "--- Liquibase: Executing Update ---"
                liquibase --driver=com.mysql.cj.jdbc.Driver \
                    --changelog-file=changelog.sql \
                    --url="jdbc:mysql://localhost:3306/royawl" \
                    --username=${DB_USER} \
                    --password=${DB_PASS} \
                    update
EOF
            """
            }
          }
        }

        stage('Execute Deployment') {

          steps {
            withCredentials([
              usernamePassword(credentialsId: 'vps-root', usernameVariable: 'USER', passwordVariable: 'PASS'),
              string(credentialsId: 'jsypt-password', variable: 'JASYPT_PASSWORD')
            ]) {
              sh """
                        sshpass -p '$PASS' ssh -T -o StrictHostKeyChecking=no root@${REMOTE_IP} << 'EOF'
                                # Load the new image
                                docker load -i ${DEPLOY_PATH}/${IMAGE_TAR}
                                
                                # Stop and remove existing container if it exists
                                docker rm -f ${APP_NAME} || true
                                
                                # Run the new container
                                docker run -d \
                                    --name ${APP_NAME} \
                                    --network royawl-bridge \
                                    --add-host=host.docker.internal:host-gateway \
                                    -p 443:443 \
                                    -v /home/ubuntu/documents/royawl:/app/data/royawl \
                                    -v /home/ubuntu/config/royawl-api/log4j2.xml:/config/log4j2.xml \
                                    -e LOG4J2_CONFIG=/config/log4j2.xml \
                                    -e SPRING_PROFILES_ACTIVE=prod \
                                    -e SPRING_OUTPUT_ANSI_ENABLED=NEVER \
                                    -e SERVER_PORT=443 \
                                    -e JASYPT_PASSWORD="${JASYPT_PASSWORD}" \
                                    -e KAFKA_OPTS="--spring.kafka.bootstrap-servers=royawl-kafka:9092" \
                                    ${IMAGE_NAME}
                                    
                                # Cleanup the tar file to save space
                                rm ${DEPLOY_PATH}/${IMAGE_TAR}
EOF
                    """
            }
          }
        }
        stage('Health Check') {

          steps {
            script {
              def HEALTH_URL = "https://api.royawl.com/system/healthcheck"
              retry(10) {
                sleep 15
                def response = sh(script: "curl -s -k ${HEALTH_URL} || echo 'failed'", returnStdout: true).trim()
                if (!response.contains('"success" : true')) {
                  error "App not healthy yet. Check logs on server."
                }
              }
            }
          }
        }
      }
    }
  }

  post {
    failure { echo "Deployment failed!" }
    success { echo "Deployment to Production successful." }
  }
}
