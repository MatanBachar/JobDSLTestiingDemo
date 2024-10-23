package com.dslexample

import com.dslexample.support.ReactorSimulator
import com.dslexample.support.TestUtil
import com.dslexample.support.JobWrapper
import org.yaml.snakeyaml.Yaml
import hudson.model.Item
import hudson.model.View
import javaposse.jobdsl.dsl.DslScriptLoader
import javaposse.jobdsl.dsl.GeneratedItems
import javaposse.jobdsl.dsl.GeneratedJob
import javaposse.jobdsl.dsl.GeneratedView
import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.plugin.JenkinsJobManagement
import jenkins.model.Jenkins
import org.junit.ClassRule
import org.jvnet.hudson.test.JenkinsRule
import spock.lang.Shared
import spock.lang.Specification

/**
 * Tests that all dsl scripts in the jobs directory will compile. All config.xml's are written to build/debug-xml.
 *
 * This runs against the jenkins test harness. Plugins providing auto-generated DSL must be added to the build dependencies.
 */
class BuildSeedSpec extends Specification {

    @Shared
    @ClassRule
    private JenkinsRule jenkinsRule = new JenkinsRule()

    @Shared
    private File outputDir = new File('./build/debug-xml')

    @Shared
    private File seedFile = new File('src/jobs/buildSeed.groovy')

    @Shared
    private Map defaults

    def setupSpec() {
        outputDir.deleteDir()
        // Load default YAML file
        defaults = new Yaml().load(new File("src/test/groovy/com/dslexample/support/reactorexamples/example1.yml").text)
    }

    void 'test script docker env var'() {
        given:
        Map parameters = new ReactorSimulator(defaults).build()
        JobManagement jm = new JenkinsJobManagement(System.out, parameters, new File('.'))
        Jenkins jenkins = jenkinsRule.jenkins

        when:
        GeneratedItems items = new DslScriptLoader(jm).runScript(seedFile.text)
        writeItems(items, outputDir)

        then:
        noExceptionThrown()

        def job = TestUtil.getJobByName(jenkins, items, 'staging_Build_OneDNN_u22_master_next_Matan_Bachar')
        job.getEnvVar("dockerImage") == "artifactory-kfs.habana-labs.com/devops-docker-local/fs-one:onednn-ubuntu2204"
    }

    /**
     * Write the config.xml for each generated job and view to the build dir.
     */
    private void writeItems(GeneratedItems items, File outputDir) {
        Jenkins jenkins = jenkinsRule.jenkins
        items.jobs.each { GeneratedJob generatedJob ->
            String jobName = generatedJob.jobName
            Item item = jenkins.getItemByFullName(jobName)
            String text = new URL(jenkins.rootUrl + item.url + 'config.xml').text
            TestUtil.writeFile(new File(outputDir, 'jobs'), jobName, text)
        }

        items.views.each { GeneratedView generatedView ->
            String viewName = generatedView.name
            View view = jenkins.getView(viewName)
            String text = new URL(jenkins.rootUrl + view.url + 'config.xml').text
            TestUtil.writeFile(new File(outputDir, 'views'), viewName, text)
        }
    }
}