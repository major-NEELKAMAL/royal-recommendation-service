pipeline {
  agent any

  triggers {
    githubPush()
  }

  environment {
    MAVEN_REPO = "/var/lib/jenkins/.m2/repository"
    DEPLOY_PATH = "/home/ubuntu/jar/jenkins/deploy"
    REMOTE_IP = "168.231.102.240"
    SSH_CRED_ID = "vps-ssh-key"   
    SSH_USER = "root"
    APP_NAME = "royawl-recommendation-service"
    IMAGE_NAME = "royawl-recommendation-service:latest"
    IMAGE_TAR = "royawl-recommendation-service.tar"
    BACKUP_IMAGE = "royawl-recommendation-service:backup-stable"
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
            sshagent(credentials: ["${env.SSH_CRED_ID}"]) {
              sh "scp -o StrictHostKeyChecking=no ${IMAGE_TAR} ${env.SSH_USER}@${REMOTE_IP}:${DEPLOY_PATH}/${IMAGE_TAR}"
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

        stage('Execute Deployment') {
          steps {
            sshagent(credentials: ["${env.SSH_CRED_ID}"]) {
              sh """
              ssh -T -o StrictHostKeyChecking=no ${env.SSH_USER}@${REMOTE_IP} << 'EOF'
                  # 1. BACKUP PREVIOUS IMAGE BEFORE TOUCHING PRODUCTION
                  if docker images | grep -q "${APP_NAME}"; then
                      echo "Creating backup of currently running stable image..."
                      docker tag ${IMAGE_NAME} ${BACKUP_IMAGE} || true
                  fi

                  # 2. Load the new image
                  echo "Loading new production image..."
                  docker load -i ${DEPLOY_PATH}/${IMAGE_TAR}
                  
                  # 3. BRING DOWN BOTH CONTAINERS
                  echo "Stopping and removing existing instances..."
                  docker rm -f ${APP_NAME}-1 2>/dev/null || true
                  docker rm -f ${APP_NAME}-2 2>/dev/null || true
                  
                  # 4. START BOTH NEW CONTAINERS
                  echo "Starting Instance 1 on Port 9081..."
                  docker run -d \\
                      --name ${APP_NAME}-1 \\
                      --network royawl-bridge \\
                      --add-host=host.docker.internal:host-gateway \\
                      -p 9081:9081 \\
                      -v /home/ubuntu/documents/royawl:/app/data/royawl \\
                      -v /home/ubuntu/config/royawl-recommendation-service/log4j2.xml:/config/log4j2.xml \\
                      -e LOG4J2_CONFIG=/config/log4j2.xml \\
                      -e SPRING_PROFILES_ACTIVE=prod \\
                      -e SPRING_OUTPUT_ANSI_ENABLED=NEVER \\
                      -e SERVER_PORT=9081 \\
                      ${IMAGE_NAME}

                  echo "Starting Instance 2 on Port 9082..."
                  docker run -d \\
                      --name ${APP_NAME}-2 \\
                      --network royawl-bridge \\
                      --add-host=host.docker.internal:host-gateway \\
                      -p 9082:9082 \\
                      -v /home/ubuntu/documents/royawl:/app/data/royawl \\
                      -v /home/ubuntu/config/royawl-recommendation-service/log4j2.xml:/config/log4j2.xml \\
                      -e LOG4J2_CONFIG=/config/log4j2.xml \\
                      -e SPRING_PROFILES_ACTIVE=prod \\
                      -e SPRING_OUTPUT_ANSI_ENABLED=NEVER \\
                      -e SERVER_PORT=9082 \\
                      ${IMAGE_NAME}
                      
                  # 5. Clean up tar file
                  rm -f ${DEPLOY_PATH}/${IMAGE_TAR}
EOF
"""
            }
          }
        }

        stage('Health Check') {
          steps {
            script {
              def HEALTH_URL = "https://api.royawl.com/api/system/healthcheck/recommendation"
              
              retry(3) {
                sleep 15
                echo "Hitting Health Check Endpoint..."
                def response = sh(script: "curl -s -k ${HEALTH_URL} || echo 'failed'", returnStdout: true).trim()
                
                if (!response.contains('"success":true') && !response.contains('"success" : true')) {
                  error "App health check failed! Forcing rollback transition."
                }
              }
            }
          }
        }
      }
    }
  }

  post {
    failure {
      script {
        echo "🚨 DEPLOYMENT FAILED! Initiating immediate production rollback via SSH Key..."
        sshagent(credentials: ["${env.SSH_CRED_ID}"]) {
          sh """
          ssh -T -o StrictHostKeyChecking=no ${env.SSH_USER}@${REMOTE_IP} << 'EOF'
              if docker images | grep -q "backup-stable"; then
                  echo "Reverting production instances to backup-stable image..."
                  
                  docker rm -f ${APP_NAME}-1 2>/dev/null || true
                  docker rm -f ${APP_NAME}-2 2>/dev/null || true
                  
                  docker run -d --name ${APP_NAME}-1 --network royawl-bridge --add-host=host.docker.internal:host-gateway -p 9081:9081 -v /home/ubuntu/documents/royawl:/app/data/royawl -v /home/ubuntu/config/royawl-recommendation-service/log4j2.xml:/config/log4j2.xml -e LOG4J2_CONFIG=/config/log4j2.xml -e SPRING_PROFILES_ACTIVE=prod -e SPRING_OUTPUT_ANSI_ENABLED=NEVER -e SERVER_PORT=9081 ${BACKUP_IMAGE}
                  
                  docker run -d --name ${APP_NAME}-2 --network royawl-bridge --add-host=host.docker.internal:host-gateway -p 9082:9082 -v /home/ubuntu/documents/royawl:/app/data/royawl -v /home/ubuntu/config/royawl-recommendation-service/log4j2.xml:/config/log4j2.xml -e LOG4J2_CONFIG=/config/log4j2.xml -e SPRING_PROFILES_ACTIVE=prod -e SPRING_OUTPUT_ANSI_ENABLED=NEVER -e SERVER_PORT=9082 ${BACKUP_IMAGE}
                  
                  echo "Rollback complete. Production has recovered to previous stable state."
              else
                  echo "Critical Error: No backup image found on VPS to roll back to!"
              fi
EOF
"""
        }
      }
    }
    success {
      echo "Deployment to Production successful."
    }
  }
}