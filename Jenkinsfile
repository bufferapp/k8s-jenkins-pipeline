#!/usr/bin/groovy

podTemplate(label: 'pipeline-cleanup-pod', containers: [
    containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:v2.4.2', ttyEnabled: true, command: 'cat'),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.6.6', ttyEnabled: true, command: 'cat')
],
volumes:[
    hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
]){
    node ('pipeline-cleanup-pod') {
        properties([
            pipelineTriggers([
                [$class: 'GenericTrigger',
                    genericVariables: [
                        [expressionType: 'JSONPath', key: 'branchName', value: '$.ref'],
                        [expressionType: 'JSONPath', key: 'refType', value: '$.ref_type'],
                        [expressionType: 'JSONPath', key: 'repoName', value: '$.repository.name']
                    ],
                    genericHeaderVariables: [
                        [key: 'X-GitHub-Event']
                    ]
                ]
            ])
        ])
        stage("helm delete") {
            sh '''
            echo Event $X_GitHub_Event branch $branchName
            '''
            container('helm') {
                def eventType = X_GitHub_Event
                def refType = refType
                if (eventType == 'delete' && refType == 'branch') {
                    def branchName = branchName.replace("/", "-")
                    def repoName = repoName
                    def releaseName = "${branchName}-${repoName}"
                    if (releaseName.length() > 63) {
                        releaseName = releaseName.substring(0, 63)
                    }
                    if (branchName) {
                        sh "helm delete --purge ${releaseName}"
                    }
                }
            }
        }
    }
}
