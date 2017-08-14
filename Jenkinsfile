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
                        [expressionType: 'JSONPath', key: 'branchName', value: '$.ref']
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
                if (eventType == 'delete') {
                    def branchName = branchName.replace("/", "-")
                    if (branchName) {
                        sh "helm delete --purge ${branchName}"
                    }
                }
            }
        }
    }
}
