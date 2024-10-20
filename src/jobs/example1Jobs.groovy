job('example-job-with-env') {
    description('Job with environment variable dockerImage')

    environmentVariables {
        env('dockerImage', DOCKER_NAME)
    }

    steps {
        shell('echo "Docker Image is $dockerImage"')
    }
}