package com.dslexample.support


class JobWrapper {
    def item

    JobWrapper(item) {
        this.item = item
    }

    /**
     * Get environment variables from the deep structure: item.properties.values()[0].info.propertiesContent
     * Returns a Map of key-value pairs extracted from the multiline string
     */
    Map<String, String> getEnvVars() {
        def propertiesContent = item?.properties?.values()?.getAt(0)?.info?.propertiesContent
        if (!propertiesContent) {
            throw new IllegalStateException("Could not find propertiesContent or it is empty")
        }

        // Parse the multiline string to a Map
        def envVars = [:]
        propertiesContent.eachLine { line ->
            def parts = line.split('=')
            if (parts.size() == 2) {
                envVars[parts[0].trim()] = parts[1].trim()
            }
        }

        return envVars
    }

    /**
     * Get a specific environment variable by key
     */
    String getEnvVar(String key) {
        def envVars = getEnvVars()
        return envVars[key]
    }
}