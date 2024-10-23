@Grab('org.yaml:snakeyaml:1.29')
import org.yaml.snakeyaml.Yaml
import groovy.json.JsonSlurper
engine = new groovy.text.SimpleTemplateEngine()


/**************************************************************************

This seed create the build job for given compoennt

***************************************************************************/

containsOsBuild = CONFIG_YML.config.containsKey('osBuild')
osBuild = containsOsBuild ? CONFIG_YML.config['osBuild'] : null
localManifestPath = REPO_MANIFEST_PATH

githubOrg = env.GITHUB_ORG?: 'gerrit'
githubDir = env.GITHUB_ORG? "${env.GITHUB_ORG}/": ''
println "githubOrg is: ${githubOrg} githubDir is: ${githubDir}"

def mainOSName = OS_LIST.find {it.value.primary == true}.key
mainOS = [
    longName: mainOSName,
    shortName: OS_LIST[mainOSName].shortName
]

def secondaryOS = ""
SECOND_OS = [
    longName: secondaryOS ?: "",
    shortName: secondaryOS ? OS_LIST[secondaryOS].shortName : ""
]

def paramsMap = [
    'staging' : [
        'prefix': 'staging_',
        'gitTriggerMap': [
            [
                'repository': COMPONENT_REPO,
                'files': [
                    '.ci/pipelines/build.jenkinsfile',
                    '.ci/pipelines/tests/k8s_templates/default_template.yaml'
                ]
            ]
        ],
        'checkoutRefspec': '${GERRIT_REFSPEC}:${GERRIT_REFSPEC}',
        'checkoutBranch': githubOrg == 'gerrit'? '${GERRIT_REFSPEC}': '${GIT_SOURCE_BRANCH}'
    ],
    'promote' : [
        'prefix': 'Promote_',
        'isPromote': 'true',
        'isDisabledOnStaging' : false
    ],
    'promote_migrate' : [
        'prefix': "Promote${SECOND_OS.shortName.toUpperCase()}_",
        'isPromote': 'true',
        'migrating_OS': secondaryOS
    ],
    'preSubmit' : [
        'gitTriggerMap': CONFIG.config['gerritTriggerMap']
    ],
    'Test_dependent' : [
        'prefix': 'Test_dependent_',
        'isDependent': 'true',
        'checkoutBranch': "${env.PRODUCT_PREFIX}${BRANCH}" - '_next'
    ],
    'bisect' : [
        'prefix': 'Bisect_',
        'checkoutRefspec': '${GIT_CHECKOUT_CUSTOM_BRANCH}:${GIT_CHECKOUT_CUSTOM_BRANCH}'
    ],
    'test' : [
        'prefix': 'Test_'
    ],
    'sanitizerConfig' : [
        'prefix': 'Sanitizer_',
        'envMap': [
            'SANITIZER': true,
            'CXX': 'c++',
            'CC':'gcc'
        ]
    ],
    'codeCoverageConfig' : [
        'prefix': 'CodeCoverage_',
        'envMap': [
            'CODE_COVERAGE': true,
        ]
    ],
    'coverityConfig' : [
        'prefix': 'Coverity_',
        'envMap': [
            'COVERITY': true,
            'BUILD_TYPE': 'coverity'
        ]
    ]
]
if (githubOrg == 'gerrit') {
    addFilesToTriggerMap (paramsMap.preSubmit, COMPONENT_REPO, ['.ci/pipelines/**', '.devops/cd/**'], false, 'files', true)
    if (!IS_LOCAL_MANIFEST){
        addFilesToTriggerMap (paramsMap.preSubmit, 'software-repo', ["${localManifestPath}"], true)
    } else {
        addFilesToTriggerMap (paramsMap.preSubmit, COMPONENT_REPO, ["${localManifestPath}"], false)
    }
    paramsMap.staging.gitTriggerMap.each { repo ->
        addFilesToTriggerMap (paramsMap.preSubmit, repo.repository, repo.files, false, 'files', true)
    }
}

//loop over paramsMap
generateBuild(paramsMap['staging'])
generateBuild(paramsMap['promote'])
generateBuild(paramsMap['preSubmit'])
generateBuild(paramsMap['Test_dependent'])
generateBuild(paramsMap['bisect'])


// creating promote build for migrating OS  - u22. will not run once u22 migrated as primary OS
if(OS.shortName != SECOND_OS.shortName && githubOrg == "gerrit"){
    generateBuild(paramsMap['promote_migrate'])
}

EXTENDED_BUILDS_CONFIG.collect { EXTENDED_BUILDS_CONFIG[it.key] ?: paramsMap[it.key] }.each { config -> generateBuild(config) }

if (containsOsBuild) {
    OS_LIST.each {  it ->
        // Skip Test_Build job for primary OS
        if (it.value.get('primary', false)) { return }
        // Skip Test_Build job for OSes set to off
        if (osBuild && osBuild[it.key] == 'off') { return }

        OS.longName = it.key
        OS.shortName = it.value.shortName
        generateBuild(paramsMap['test'])
    }
}

def generateBuild(paramsMap) {
    paramsMap.envMap = paramsMap.envMap ?: [:] // init envMap if null
    def prefix = paramsMap.prefix ?: ''
    def isMigratingOS = paramsMap.migrating_OS ? true : false
    def isPrimaryOS = (prefix != 'Test_' && isMigratingOS == false)
    def os_shortName = isMigratingOS ? SECOND_OS.shortName : OS.shortName
    def os_longName = isMigratingOS ? SECOND_OS.longName : OS.longName

    String jobName = "${prefix}Build_${COMPONENT_NAME}_${os_shortName}_${BRANCH_NAME}${TEST_SUFFIX}"
    // dependentJobName and dependentJobFolder are used only for Test_Build jobs
    String dependentJobName = "Build_${COMPONENT_NAME}_${mainOS.shortName}_${BRANCH_NAME}"
    String dirName = "${STAGING_PREFIX}${COMPONENT_NAME}/"
    String dependentJobFolder = dirName - STAGING_PREFIX
    Boolean silentMode = CONFIG.config.containsKey('isSilentBuild')? CONFIG.config.isSilentBuild : false

    if(prefix.contains("Coverity"))
    {
        CONFIG.config.jobTimeout = "360"
    }
    if(prefix.contains("Sanitizer"))
    {
        CONFIG.config.jobTimeout = "240"
    }
    // Add to Environnement map ccache information
    if ( Boolean.valueOf(CONFIG.config.sharedCCacheEnabled) ) {
        // path to remote share mounted on machine
        paramsMap.envMap["DOCKER_BUILD_CACHE_VOLUME"] = "/software/.ci_ccache/${COMPONENT_NAME}/${os_longName}/"
        paramsMap.envMap["CCACHE_MAXSIZE"] = CONFIG.config?.ccache?.ccacheMaxsize ?: "30G"  // set size for shared CI ccache - default is 30G
    } else {
        paramsMap.envMap["DOCKER_CCACHE_OS"] = os_longName // legacy approach to mount in /var/lib/docker/volume
    }

    pipelineJob("${jobName}") {
        description "Build job for ${COMPONENT_NAME},\nThis Job generated via DSL any manual modification will be override"
        // Disabling jobs only in gerrit context, in github we are trusting the dispatcher job
        disabled((paramsMap.containsKey('isDisabledOnStaging') ? paramsMap.isDisabledOnStaging : IS_STAGING) && githubOrg == 'gerrit')

        if (paramsMap.containsKey('gitTriggerMap')) {
            if (githubOrg == 'gerrit') {
                triggers {
                    gerrit {
                        events {
                            patchsetCreated()
                        }
                        configure { gerrit ->
                            gerrit.appendNode('serverName', 'gerrit-dev')
                            if ( prefix == "Test_")
                            {
                                if (OS_LIST[os_longName].silentMode){
                                    println("Global silent mode for OS: ${os_longName} enabled")
                                    silentMode = true
                                }
                                if (osBuild.get('silentMode', false) || osBuild[os_longName] == 'silent'){
                                    silentMode = true
                                }
                                if (osBuild[os_longName] == 'on') {
                                    println("Silent mode disabled by config.yaml for OS: ${os_longName}")
                                }
                                gerrit.appendNode('silentMode', silentMode)
                                gerrit.appendNode('dependencyJobsNames', dependentJobName ? "${dependentJobFolder}${dependentJobName},${dependentJobFolder}staging_${dependentJobName}" : '')
                            }
                            else {
                                gerrit.appendNode('silentMode', IS_STAGING?: silentMode)
                            }

                            //try to set it up without the configure
                            gerrit / 'triggerOnEvents'{
                                'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.events.PluginCommentAddedContainsEvent' {
                                    commentAddedCommentContains('recheck')
                                }
                                'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.events.PluginPatchsetCreatedEvent' {
                                    excludeDrafts('true')
                                    excludeTrivialRebase('false')
                                    excludeNoCodeChange('true')
                                    excludePrivateState('false')
                                    excludeWipState('true')
                                    commitMessageContainsRegEx('')
                                }
                            }

                            gerrit / 'gerritProjects'{
                            paramsMap['gitTriggerMap'].each { repo ->
                                    'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject'{
                                        compareType('PLAIN')
                                        pattern(repo['repository'])
                                        'branches'{
                                            'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch'{
                                                compareType('PLAIN')
                                                pattern(repo.containsKey('isStable') ? (repo.isStable ? BRANCH - '_next' : BRANCH) : BRANCH)
                                            }
                                        }
                                        if(repo.containsKey("files") || repo.containsKey('regex')) {
                                            'filePaths'{
                                                if(repo.containsKey("files")) {
                                                    repo['files'].each { file ->
                                                        'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath'{
                                                            compareType('ANT')
                                                            pattern(file)
                                                        }
                                                    }
                                                }
                                                if(repo.containsKey("regex")) {
                                                    repo['regex'].each { file ->
                                                        'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath'{
                                                            compareType('REG_EXP')
                                                            pattern(file)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if(repo.containsKey('forbiddenFiles') || repo.containsKey('forbiddenRegex')) {
                                            'forbiddenFilePaths'{
                                                if (repo.containsKey('forbiddenFiles')) {
                                                    repo['forbiddenFiles'].each { file ->
                                                        'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath'{
                                                            compareType('ANT')
                                                            pattern(file)
                                                        }
                                                    }
                                                }
                                                if (repo.containsKey('forbiddenRegex')) {
                                                    repo['forbiddenRegex'].each { file ->
                                                        'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath'{
                                                            compareType('REG_EXP')
                                                            pattern(file)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        def buildDockerImage = generateBuildDockerImage(isPrimaryOS, os_longName)
        println "For ${prefix}, docker image that will be used: ${buildDockerImage}"

        environmentVariables{
            envs(paramsMap.envMap?: [:])
            envs(
                COMPONENT_NAME: COMPONENT_NAME,
                COMPONENT_REPO_MANIFEST: localManifestPath,
                COMPONENT_REPO: COMPONENT_REPO,
                COMPONENT_GITHUB_ORG: env.GITHUB_ORG,
                RELEASE_VERSION: RELEASE_VERSION,
                OS: os_longName,
                OS_SHORT_NAME: os_shortName,
                IS_LOCAL_MANIFEST: IS_LOCAL_MANIFEST,
                IS_PRIMARY_OS: isPrimaryOS,
                IS_PROMOTE: paramsMap.isPromote?: 'false',
                IS_DEPENDENT_COMPONENT: paramsMap.isDependent?: 'false',
                dockerImage: buildDockerImage,
                runInDocker: CONFIG.config.environmentVariables?.runInDocker?: 'true',
                PRODUCT: env.PRODUCT
            )
        }

        parameters {
            booleanParam("fullBuild", false, "By default the job poll the git repos without cleaning the option is for faster debug runs relaying on the previous build environment to be available")
            booleanParam("isConfigure", CONFIG.config.isConfigure?: false, "-c,  --configure     Configure before build (doesn\'t rebuild boost if exists)")
            booleanParam("isResetBefore", false, "Run git reset --hard - disabled by default")
            booleanParam("cleanRepo", false, "Default - Incremental build - keep previous build products. Full build - remove previous build products")
            stringParam("release_branch", "${BRANCH}", "Running Branch")
            choiceParam("buildType", ['Release', 'Debug', 'Debug_And_Release'], '''Release - -r, --release (Build only release build)
            Debug             - -default (build type debug)
            Debug_And_Release - -a,  --build-all (Build both debug and release build)''')
            stringParam('builder', CONFIG.config.builder?: 'build-generic', 'Node Label')
            if (githubOrg == 'gerrit'){
                stringParam("GERRIT_BRANCH", "${BRANCH}", "")
                stringParam("GERRIT_PROJECT", "${COMPONENT_REPO}", "the gerrit project which used for clone the code, by default '${COMPONENT_REPO}'")
                stringParam("GERRIT_CHANGE_NUMBER", '', '')
                stringParam("GERRIT_PATCHSET_NUMBER", '', '')
            } else {
                stringParam("GIT_TARGET_BRANCH", "${BRANCH}", "PR Terget Branch, which will be passed from presubmit orchestrator")
                stringParam("GIT_PROJECT", "${COMPONENT_REPO}", "the git project which used for clone the code, by default '${COMPONENT_REPO}'")
                stringParam("GIT_TRIGGER", '', '')
                stringParam("GIT_SOURCE_BRANCH", '', '')
                stringParam("GIT_TITLE", '', '')
                stringParam("GIT_CHANGE_URL", '', '')
                stringParam("GIT_COMMIT_OWNER_NAME", '', '')
                stringParam("GIT_COMMIT_OWNER_EMAIL", '', '')
                stringParam("GIT_REFSPEC", '', '')
                stringParam("GIT_CHANGE_ID", '', '')
                stringParam("GIT_ORGANIZATION", '', '')
                booleanParam("buildOnly", false, "In case of component which builds test artifacts in addition to it's build, turn flag on to only build component") // Only used on FS projects
            }
            stringParam("GERRIT_PATCHSET_REVISION", '', '')
            stringParam("GIT_CHECKOUT_CUSTOM_BRANCH", "${BRANCH}", "Dedicated parameter to CheckoutSCM script from")
            stringParam("GIT_REVISION", '', '')
            stringParam("TRIGGER_BUILD", '', '')
            stringParam("TRIGGER_BUILD_NUMBER", '', '')
            stringParam("TRIGGERED_BY", '', '(technical parameter) the parent job trigger this job. DO NOT MODIFY')
            booleanParam("isSilent", silentMode, "is triggered in silent mode")
            stringParam("projectsRevisions", '', '(Optional) used to checkout different revisions from the manifest for the component\'s projects')
            stringParam("jobTimeout", CONFIG.config.jobTimeout?: "300", "job timeout (minutes)" )
            textParam("manifest", "", "Custom manifest used for build" )
            CONFIG.config.buildJobParameters.each{ parameter ->
                if(parameter.paramType == "booleanParam"){
                    "${parameter.paramType}"(parameter.paramName, parameter.paramDefaultValue ? parameter.paramDefaultValue.toBoolean() : false , parameter.paramDescription ?: "")
                }
                else{
                    "${parameter.paramType}"(parameter.paramName, parameter.paramDefaultValue ?: "" , parameter.paramDescription ?: "")
                }
            }
        }

        def checkoutRefspec = githubOrg == 'gerrit' ? (paramsMap.checkoutRefspec ?: '') : ''
        def checkoutBranch = paramsMap.checkoutBranch ?: BRANCH
        def componentRepo = paramsMap.componentRepo ?: COMPONENT_REPO
        def repoUrl = githubOrg == 'gerrit' ? "ssh://gerrit:29418/${componentRepo}.git" : "ssh://git@github.com/${githubOrg}/${componentRepo}.git"
        def repoCredentials = githubOrg == 'gerrit' ? "${GERRIT_CREDENTIALS_ID}" : 'jenkins'

        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            refspec(checkoutRefspec)
                            url(repoUrl)
                            credentials(repoCredentials)
                        }
                        branch( prefix.contains("Bisect") ? '${GIT_CHECKOUT_CUSTOM_BRANCH}' : checkoutBranch )
                        if (prefix.contains("Bisect")) {
                            extensions {
                                wipeWorkspace{}
                            }
                        }
                    }
                }
                scriptPath(paramsMap.pipeline ?: '.ci/pipelines/build.jenkinsfile')
            }
        }
    }
}

//TODO - move to a shared place so both the build seed and launcher seed will be able to use it
//filesType can be files or regex
def addFilesToTriggerMap(config, repository, files, isStable = false, filesType = 'files', isForbidden = false) {
    def index = config.gitTriggerMap.repository.indexOf(repository)
    def branch = isStable ? BRANCH - '_next' : BRANCH
    filesType = isForbidden ? 'forbidden' + filesType.capitalize() : filesType
    // if the repository exists in the trigger map
    if (index != -1 &&
    (
        // isStable is not set in it and the we want the unstable branch
        (!config.gitTriggerMap[index].containsKey('isStable') && isStable == false) ||
        // or isStable is set in it and it matches the function param
        (config.gitTriggerMap[index].isStable == isStable)
    )) {
        // if its already containing files map
        if(config.gitTriggerMap[index].containsKey(filesType)) {
            // append to the files map
            config.gitTriggerMap[index][filesType] += files
        }
        else {
            // in case that the current trigger map listens to all of the repository - we dont want to narrow it down, only to add forbidden files
            if(isForbidden || (config.gitTriggerMap[index].containsKey("files") || config.gitTriggerMap[index].containsKey("regex"))) {
                // create a new files map with the files
                config.gitTriggerMap[index][filesType] = files
            }
        }
    }
    else {
        // if we only add forbidden files we dont need to listen at all
        if(!isForbidden) {
            if(filesType == 'files') {
                // create a new repository listening entry
                config.gitTriggerMap += [
                    'repository': repository,
                    'files': files,
                    'isStable': isStable
                ]
            }
            // if we only add forbidden files we dont need to listen at all
            if(filesType == 'regex') {
                // create a new repository listening entry
                config.gitTriggerMap += [
                    'repository': repository,
                    'regex': files,
                    'isStable': isStable
                ]
            }
        }
    }
}


/*
 * This function generates the name for the docker that will be used for build
 * in case COMPONENT_SPECIFIC_DOCKER is true, the docker will be created with the following
 * template:
 *
 * <default_name>/<component_name>:<os_longname>-<release_version>
*/

def generateBuildDockerImage(Boolean isPrimaryOS, String osLongName) {
    Boolean isComponentSpecificDocker = binding.hasVariable('COMPONENT_SPECIFIC_DOCKER') ? COMPONENT_SPECIFIC_DOCKER : false
    def imageTag = "${osLongName}-${RELEASE_VERSION}"
    def dockerImageName = CONFIG.config.environmentVariables?.dockerImage

    // In case the docker is not defined as envVar or the job is not primary os, the default docker is chosen
    if (!dockerImageName || !isPrimaryOS) {
        return "${DEFAULT_DOCKER_IMAGE}${isComponentSpecificDocker ? "/${COMPONENT_NAME.toLowerCase()}" : ''}:${imageTag}"
    }
    return dockerImageName.contains(':') ? dockerImageName : "${dockerImageName}:${imageTag}"
}