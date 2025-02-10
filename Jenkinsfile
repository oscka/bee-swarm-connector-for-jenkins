def version
def binName
def uploadName
def uploadUrl

pipeline{
  options { 
    disableConcurrentBuilds() 
  }

  environment {
    REGISTRY = "https://cart.lge.com"
    REGISTRY_CREDENTIAL = 'cart_bee_internal'
    TEAMS_WEBHOOK_URL = "https://prod-44.southeastasia.logic.azure.com:443/workflows/57da8636f3ff43cbbf47dbebc47c74a2/triggers/manual/paths/invoke?api-version=2016-06-01&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=9UBxhek0pJZlYcIWdK02Yur_Qr3sYD136SacAhcpTTU"
  }

  agent none
  stages {
    stage('Node info - linux') {
      agent { label 'bee_stg' }
      options {
        skipDefaultCheckout true
      }
      steps {
          echo '===Node Info==='
          sh 'pwd'
          sh 'whoami'
          sh 'ifconfig'
        }
    }

    stage('[Bee] Build Swarm Connector for Jenkins') {
      agent { label 'bee_stg' }
      steps {
        script{
          echo '===Build BEE Swarm Connector for Jenkins for ${branch}==='
          sh './build.sh'
          }
        }
      }

    stage('[Bee] Push Swarm Connector for Jenkins') {
      agent { label 'bee_stg' }
      options {
        skipDefaultCheckout true
      }
      steps {
        script{
          version = sh(script:"grep -oP '(?<=revision>)[^<]+' ./pom.xml", returnStdout: true).trim()
          binName = "bee.plugin.swarm.connector.hpi"
          if (env.BRANCH_NAME == 'develop') {
            uploadName = "bee.plugin.swarm.connector-develop.hpi"
            uploadUrl = "http://cart.lge.com/artifactory/bee-deploy/bee-swarm-connector/jenkins/test/${version}/${uploadName}"
            uploadLatestUrl = "http://cart.lge.com/artifactory/bee-deploy/bee-swarm-connector/jenkins/test/latest/${uploadName}"
          }else if (env.BRANCH_NAME == 'master') {
            uploadName = "bee.plugin.swarm.connector.hpi"
            uploadUrl = "http://cart.lge.com/artifactory/bee-deploy/bee-swarm-connector/jenkins/${version}/${uploadName}"
            uploadLatestUrl = "http://cart.lge.com/artifactory/bee-deploy/bee-swarm-connector/jenkins/latest/${uploadName}"
          }

          withCredentials([usernamePassword(credentialsId: REGISTRY_CREDENTIAL, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
            withEnv(["binName=$binName", "uploadUrl=$uploadUrl", "uploadLatestUrl=$uploadLatestUrl"])
            {
                sh 'curl -u ${USERNAME}:${PASSWORD} -T ${binName} ${uploadUrl}'
                sh 'curl -u ${USERNAME}:${PASSWORD} -T ${binName} ${uploadLatestUrl}'
            }
          }
        }
      }
    }
  }

  post {
    success {
      office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK_URL}",
                             factDefinitions: [[name: "Job Name", template: "${env.JOB_NAME}"],
                                               [name: "Build Number", template: "${env.BUILD_NUMBER}"],
                                               [name: "Build Url", template: "${env.BUILD_URL}console"]]
    }
    failure {
      office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK_URL}",
                             factDefinitions: [[name: "Job Name", template: "${env.JOB_NAME}"],
                                               [name: "Build Number", template: "${env.BUILD_NUMBER}"],
                                               [name: "Build Url", template: "${env.BUILD_URL}console"]]
    }
    unstable {
      office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK_URL}",
                             factDefinitions: [[name: "Job Name", template: "${env.JOB_NAME}"],
                                               [name: "Build Number", template: "${env.BUILD_NUMBER}"],
                                               [name: "Build Url", template: "${env.BUILD_URL}console"]]
    }
  }
}
