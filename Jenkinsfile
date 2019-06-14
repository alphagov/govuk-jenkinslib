#!/usr/bin/env groovy
library('govuk')

pipeline {
    agent 'any'
    tools {
        maven 'mvn'
        jdk 'jdk8'
    }
    options {
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Test') {
            steps {
                sh 'mvn clean test'
            }
            post {
                always {
                    junit(keepLongStdio: true, testResults: "target/surefire-reports/junit-*.xml,target/surefire-reports/TEST-*.xml")
                }
            }
        }
        stage('Pipeline Test') {
            govuk.pipelineTest()
        }
    }
}
