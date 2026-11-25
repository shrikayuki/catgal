pipeline {
    agent { label 'built-in' }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    stages {
        stage('Checkout main') {
            steps {
                checkout scm
            }
        }

        stage('Test, build and release') {
            steps {
                sh 'git diff --exit-code HEAD && python3 deploy/jenkins/release_queue.py submit --timeout 2400'
            }
        }
    }
}
