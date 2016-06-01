#!groovy

node {
    stage 'Checkout'
    checkout scm

    stage 'Build'
    def mvn = "${tool 'm3'}/bin/mvn"
    sh "${mvn} clean package dependency:copy-dependencies"

    stage 'Image'
    sh 'git rev-parse --short HEAD > git_commit_id'
    commit = readFile('git_commit_id').trim()
    def img = docker.build "${env.ECR_REPOSITORY_URI}/the-train:${commit}"

    stage 'Push'
    sh '$(aws ecr get-login)'
    img.push()
}