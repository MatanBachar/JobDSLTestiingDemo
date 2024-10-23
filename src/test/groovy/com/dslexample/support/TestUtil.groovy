package com.dslexample.support

import groovy.io.FileType
import com.dslexample.support.JobWrapper
import javaposse.jobdsl.dsl.GeneratedItems
import jenkins.model.Jenkins


class TestUtil {

    static List<File> getJobFiles() {
        List<File> files = []
        new File('src/jobs').eachFileRecurse(FileType.FILES) {
            if (it.name.endsWith('.groovy')) {
                files << it
            }
        }
        files
    }

    /**
     * Write a single XML file, creating any nested dirs.
     */
    static void writeFile(File dir, String name, String xml) {
        List tokens = name.split('/')
        File folderDir = tokens[0..<-1].inject(dir) { File tokenDir, String token ->
            new File(tokenDir, token)
        }
        folderDir.mkdirs()

        File xmlFile = new File(folderDir, "${tokens[-1]}.xml")
        xmlFile.text = xml
    }

    static JobWrapper getJobByName(Jenkins jenkins, GeneratedItems items, String jobName) {
        def job = items.jobs.find { it.jobName == jobName }
        try {
            return new JobWrapper(jenkins.getItemByFullName(job.jobName))
        } catch (NullPointerException e) {
            throw new NoSuchElementException("Job ${jobName} was not found in jenkins controller")
        }
    }
}