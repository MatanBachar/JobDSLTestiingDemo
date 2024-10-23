package com.dslexample.support

import org.yaml.snakeyaml.Yaml
import groovy.io.FileType



class ReactorSimulator {
    Map parameters = [:]
    Map defaults = [:]

    ReactorSimulator(Map defaults) {
        this.defaults = defaults
    }

    ReactorSimulator componentName(String name) {
        parameters['COMPONENT_NAME'] = name
        return this
    }
    ReactorSimulator componentRepo(String name) {
        parameters['COMPONENT_REPO'] = name
        return this
    }
    ReactorSimulator branch(String name) {
        parameters['BRANCH'] = name
        return this
    }
    ReactorSimulator branchName(String name) {
        parameters['BRANCH_NAME'] = name
        return this
    }
    ReactorSimulator testSuffix(String name) {
        parameters['TEST_SUFFIX'] = name
        return this
    }
    ReactorSimulator stagingPrefix(String name) {
        parameters['STAGING_PREFIX'] = name
        return this
    }
    ReactorSimulator defaultDockerImage(String name) {
        parameters['DEFAULT_DOCKER_IMAGE'] = name
        return this
    }
    ReactorSimulator workspace(String name) {
        parameters['WORKSPACE'] = name
        return this
    }
    ReactorSimulator repoManifestPath(String name) {
        parameters['REPO_MANIFEST_PATH'] = name
        return this
    }
    ReactorSimulator componentSpecificDocker(String name) {
        parameters['COMPONENT_SPECIFIC_DOCKER'] = name
        return this
    }

    Map build() {
        // Merge the scenario map with defaults
        parameters = mergeWithDefaults(parameters, defaults)
        return defaults
    }

    private Map mergeWithDefaults(Map userScenario, Map defaultScenario) {
        // Recursive merge of user-provided values and defaults
        userScenario.collectEntries { key, value ->
            if (value instanceof Map && defaultScenario[key] instanceof Map) {
                [(key): mergeWithDefaults(value, defaultScenario[key])]
            } else {
                [(key): value ?: defaultScenario[key]]
            }
        } + defaultScenario.findAll { !(it.key in userScenario.keySet()) }
        return userScenario
    }

    Map<String, Object> getSeedConfig() {
        List<File> files = []
        Yaml yaml = new Yaml()
        String content = new File('src/test/groovy/com/dslexample/support/reactorexamples/example1.yml').text
        Map<String, Object> parsedData = yaml.load(content)
        parsedData
    }
}