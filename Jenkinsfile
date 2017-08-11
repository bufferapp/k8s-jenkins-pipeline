podTemplate(label: 'pipeline-pod', containers: [
    containerTemplate(name: 'docker', image: 'docker:17.06.0', ttyEnabled: true, command: 'cat'),
    containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:v2.4.2', ttyEnabled: true, command: 'cat'),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.6.6', ttyEnabled: true, command: 'cat'),
    containerTemplate(name: 'node', image: 'node:8.1.3', ttyEnabled: true, command: 'cat')
],
volumes:[
    hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
]){

  node ('pipeline-pod') {
   properties([
      pipelineTriggers([
       [$class: 'GenericTrigger',
        genericVariables: [
         [expressionType: 'JSONPath', key: 'hook_id', value: '$.hook_id'],
         [expressionType: 'JSONPath', key: 'zen', value: '$.zen']
        ],
        genericHeaderVariables: [
            [key: 'X-GitHub-Event', regexpFilter: '']
        ]
       ]
      ])
    ])
    stage("build") {
    sh '''
    echo Build $zen before $hook_id in $X_GitHub_Event
    '''
    }
    }
}
