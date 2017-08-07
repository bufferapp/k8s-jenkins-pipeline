#!/usr/bin/groovy

package com.buffer;

def gitVars() {
    def rv = [:]
    // create git vars
    println "Getting Git vars"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        rv.put('GIT_COMMIT_ID', readFile('git_commit_id.txt').trim())
    } catch (e) {
        error "${e}"
    }
    println "GIT_COMMIT_ID ==> ${rv['GIT_COMMIT_ID']}"

    rv.put('BRANCH_NAME', env.BRANCH_NAME.replace("/", "-"))

    return rv
}

def getContainerTags(config, Map tags = [:]) {

    println "getting list of tags for container"
    def String commit_tag
    def String version_tag

    try {
        // if PR branch tag with only branch name
        if (config.BRANCH_NAME.contains('PR')) {
            commit_tag = config.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: commit unavailable from config. ${e}"
    }

    // commit tag
    try {
        // if branch available, use as prefix, otherwise only commit hash
        if (config.BRANCH_NAME) {
            commit_tag = config.BRANCH_NAME + '-' + config.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = config.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: commit unavailable from config. ${e}"
    }

    // master tag
    try {
        if (config.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: branch unavailable from config. ${e}"
    }

    // build tag only if none of the above are available
    if (!tags) {
        try {
            tags << ['build': config.BUILD_TAG]
        } catch (Exception e) {
            println "WARNING: build tag unavailable from config.project. ${e}"
        }
    }

    return tags
}

@NonCPS
def getMapValues(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
}

def containerBuildPub(Map args) {

    for (int i = 0; i < args.tags.size(); i++) {
        println "Running Docker build/publish: ${args.repo}:${args.tags[i]} with credentials in Jenkins ${args.auth_id}"
        sh "docker build -t ${args.repo}:${args.tags[i]} ./"
        sh "docker push ${args.repo}:${args.tags[i]}"
    }
}

def start(String configFile) {

    podTemplate(label: 'pipeline-pod', containers: [
        containerTemplate(name: 'docker', image: 'docker:17.06.0', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'node', image: 'node:8.1.3', ttyEnabled: true, command: 'cat')
    ],
    volumes:[
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
    ]){

      node ('pipeline-pod') {
        checkout scm
        // read in required jenkins workflow config values
        def inputFile = readFile(configFile)
        def config = new groovy.json.JsonSlurperClassic().parseText(inputFile)
        config = config + gitVars()
        println "pipeline config ==> ${config}"

        def pwd = pwd()
        def chart_dir = "${pwd}/${config.app.name}"

        // tag image with version, and branch-commit_id
        def image_tags_map = getContainerTags(config)

        // compile tag list
        def image_tags_list = getMapValues(image_tags_map)

        if (fileExists('pre-build.sh')) {
            println "Found pre-build"
                stage ('Bundling and Uploading Static Assets') {
                container('node') {
                    println "${env.AWS_ACCESS_KEY_ID} and ${env.AWS_SECRET_ACCESS_KEY}"
                    sh "AWS_ACCESS_KEY_ID=${env.AWS_ACCESS_KEY_ID} AWS_SECRET_ACCESS_KEY=${env.AWS_SECRET_ACCESS_KEY} ./pre-build.sh"
                }
            }
        }

        stage ('Publish Container') {
          container('docker') {
            // perform docker login to quay as the docker-pipeline-plugin doesn't work with the next auth json format
            withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: config.container_repo.jenkins_creds_id,
                            usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
              println "Logging in to Docker Registry - Username: ${env.USERNAME}, Password: ${env.PASSWORD}"
              sh "docker login -u ${env.USERNAME} -p ${env.PASSWORD}"
            }

            // build and publish container
            containerBuildPub(
                repo      : config.container_repo.repo,
                tags      : image_tags_list,
                auth_id   : config.container_repo.jenkins_creds_id
            )
          }
        }
      }
    }
  }