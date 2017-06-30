#!/usr/bin/groovy

package com.buffer;

def gitEnvVars() {
    // create git envvars
    println "Setting envvars to tag container"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"

    sh 'git config --get remote.origin.url> git_remote_origin_url.txt'
    try {
        env.GIT_REMOTE_URL = readFile('git_remote_origin_url.txt').trim()
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_REMOTE_URL ==> ${env.GIT_REMOTE_URL}"
}

def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"

}

def helmConfig() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initiliazing helm client"
    sh "helm init"
    println "checking client/server version"
    sh "helm version"
}

def getContainerTags(config, Map tags = [:]) {

    println "getting list of tags for container"
    def String commit_tag
    def String version_tag

    try {
        // if PR branch tag with only branch name
        if (env.BRANCH_NAME.contains('PR')) {
            commit_tag = env.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // commit tag
    try {
        // if branch available, use as prefix, otherwise only commit hash
        if (env.BRANCH_NAME) {
            commit_tag = env.BRANCH_NAME + '-' + env.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = env.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // master tag
    try {
        if (env.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: branch unavailable from env. ${e}"
    }

    // build tag only if none of the above are available
    if (!tags) {
        try {
            tags << ['build': env.BUILD_TAG]
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

def helmLint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"

}

def helmDeploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmConfig()

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} --set image.tag=${args.version_tag} --namespace=${args.namespace}"
    } else {
        println "Running deployment"
        sh "helm upgrade --install --wait ${args.name} ${args.chart_dir} --set image.tag=${args.version_tag} --namespace=${args.namespace}"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmTest(Map args) {
    println "Running Helm test"

    sh "helm test ${args.name} --cleanup"
}

def containerBuildPub(Map args) {

    for (int i = 0; i < args.tags.size(); i++) {
        println "Running Docker build/publish: ${args.repo}:${args.tags[i]} with credentials in Jenkins ${args.auth_id}"
        sh "docker build -t ${args.repo}:${args.tags[i]} ./"
        sh "docker push ${args.repo}:${args.tags[i]}"
    }
}

def start(Map config) {

    podTemplate(label: 'pipeline-pod', containers: [
        containerTemplate(name: 'docker', image: 'docker:17.06.0', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:v2.4.2', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.6.6', ttyEnabled: true, command: 'cat')
    ],
    volumes:[
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
    ]){

      node ('pipeline-pod') {

        def pwd = pwd()
        def chart_dir = "${pwd}/${config.app.name}"

        checkout scm

        // continue only if pipeline enabled
        if (!config.pipeline.enabled) {
            println "pipeline disabled"
            return
        }

        // set additional git envvars for image tagging
        gitEnvVars()

        // If pipeline debugging enabled
        if (config.pipeline.debug) {
          println "DEBUG ENABLED"
          sh "env | sort"

          println "Runing kubectl/helm tests"
          container('kubectl') {
            kubectlTest()
          }
          container('helm') {
            helmConfig()
          }
        }

        // tag image with version, and branch-commit_id
        def image_tags_map = getContainerTags(config)

        // compile tag list
        def image_tags_list = getMapValues(image_tags_map)

        stage ('Test Helm Chart Deployment') {
          container('helm') {
            // run helm chart linter
            helmLint(chart_dir)

            // run dry-run helm chart installation
            helmDeploy(
              dry_run       : true,
              name          : config.app.name,
              namespace     : config.app.namespace,
              version_tag   : image_tags_list.get(0),
              chart_dir     : chart_dir
            )
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

        // deploy only the master branch
        if (env.BRANCH_NAME == 'master') {
          stage ('Deploy to k8s with Helm') {
            container('helm') {
              // Deploy using Helm chart
              helmDeploy(
                dry_run       : false,
                name          : config.app.name,
                namespace     : config.app.namespace,
                version_tag   : image_tags_list.get(0),
                chart_dir     : chart_dir
              )

              //  Run helm tests
              if (config.app.test) {
                helmTest(
                  name          : config.app.name
                )
              }
            }
          }
        }
      }
    }
}
