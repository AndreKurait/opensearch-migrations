def call(Map config = [:]) {
    k8sLocalDeployment(
            gitUrl: config.gitUrl,
            gitBranch: config.gitBranch,
            jobName: 'elasticsearch-8x-k8s-local-test',
            sourceVersion: 'ES_8.x',
            targetVersion: 'OS_2.x'
    )
}