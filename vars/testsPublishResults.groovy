import static com.sap.piper.Prerequisites.checkScript

import com.sap.piper.GenerateDocumentation
import com.sap.piper.ConfigurationHelper
import com.sap.piper.JenkinsUtils
import com.sap.piper.MapUtils
import com.sap.piper.Utils
import groovy.transform.Field

@Field List TOOLS = [
    /**
     * Publishes test results files in JUnit format with the [JUnit Plugin](https://plugins.jenkins.io/junit).
     * @possibleValues `true`, `false`, `Map`
     */
    'junit',
    /**
     * Publishes code coverage with the [JaCoCo plugin](https://plugins.jenkins.io/jacoco).
     * @possibleValues `true`, `false`, `Map`
     */
    'jacoco',
    /**
     * Publishes code coverage with the [Cobertura plugin](https://plugins.jenkins.io/cobertura).
     * @possibleValues `true`, `false`, `Map`
     */
    'cobertura',
    /**
     * Publishes performance test results with the [Performance plugin](https://plugins.jenkins.io/performance).
     * @possibleValues `true`, `false`, `Map`
     */
    'jmeter',
    /**
     * Publishes performance test results with the [Cucumber plugin](https://plugins.jenkins.io/performance).
     * @possibleValues `true`, `false`, `Map`
     */
    'cucumber'

]

@Field def STEP_NAME = getClass().getName()

@Field Set GENERAL_CONFIG_KEYS = TOOLS

@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS.plus([
    /**
     * If it is set to `true` the step will fail the build if JUnit detected any failing tests.
     * @possibleValues `true`, `false`
     */
    'failOnError'
])

@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS

/**
 * This step can publish test results from various sources.
 */
@GenerateDocumentation
void call(Map parameters = [:]) {
    handlePipelineStepErrors (stepName: STEP_NAME, stepParameters: parameters) {
        def script = checkScript(this, parameters) ?: this

        prepare(parameters)

        // load default & individual configuration
        Map configuration = ConfigurationHelper.newInstance(this)
            .loadStepDefaults()
            .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
            .mixinStepConfig(script.commonPipelineEnvironment, STEP_CONFIG_KEYS)
            .mixinStageConfig(script.commonPipelineEnvironment, parameters.stageName ?: env.STAGE_NAME, STEP_CONFIG_KEYS)
            .mixin(parameters, PARAMETER_KEYS)
            .use()

        new Utils().pushToSWA([
            step: STEP_NAME,
            stepParamKey1: 'scriptMissing',
            stepParam1: parameters?.script == null
        ], configuration)

        publishJUnitReport(configuration.get('junit'))
        publishJacocoReport(configuration.get('jacoco'))
        publishCoberturaReport(configuration.get('cobertura'))
        publishJMeterReport(configuration.get('jmeter'))
        publishCucumberReport(configuration.get('cucumber'))

        if (configuration.failOnError && JenkinsUtils.hasTestFailures(script.currentBuild)) {
            script.currentBuild.result = 'FAILURE'
            error "[${STEP_NAME}] Some tests failed!"
        }
    }
}

def publishJUnitReport(Map settings = [:]) {
    if(settings.active){
        def pattern = settings.get('pattern')
        def allowEmpty = settings.get('allowEmptyResults')

        if (settings.get('updateResults'))
            touchFiles(pattern)
        junit(
            testResults: pattern,
            allowEmptyResults: allowEmpty,
            healthScaleFactor: 100.0,
        )
        archiveResults(settings.get('archive'), pattern, allowEmpty)
    }
}

def publishJacocoReport(Map settings = [:]) {
    if(settings.active){
        def pattern = settings.get('pattern')
        def allowEmpty = settings.get('allowEmptyResults')

        jacoco(
            execPattern: pattern,
            inclusionPattern: settings.get('include', ''),
            exclusionPattern: settings.get('exclude', '')
        )
        archiveResults(settings.get('archive'), pattern, allowEmpty)
    }
}

def publishCoberturaReport(Map settings = [:]) {
    if(settings.active){
        def pattern = settings.get('pattern')
        def allowEmpty = settings.get('allowEmptyResults')

        cobertura(
            coberturaReportFile: pattern,
            onlyStable: settings.get('onlyStableBuilds'),
            failNoReports: !allowEmpty,
            failUnstable: false,
            failUnhealthy: false,
            autoUpdateHealth: false,
            autoUpdateStability: false,
            maxNumberOfBuilds: 0
        )
        archiveResults(settings.get('archive'), pattern, allowEmpty)
    }
}

// publish Performance Report using "Jenkins Performance Plugin" https://wiki.jenkins.io/display/JENKINS/Performance+Plugin
def publishJMeterReport(Map settings = [:]){
    if(settings.active){
        def pattern = settings.get('pattern')

        perfReport(
            sourceDataFiles: pattern,
            errorFailedThreshold: settings.get('errorFailedThreshold'),
            errorUnstableThreshold: settings.get('errorUnstableThreshold'),
            errorUnstableResponseTimeThreshold: settings.get('errorUnstableResponseTimeThreshold'),
            relativeFailedThresholdPositive: settings.get('relativeFailedThresholdPositive'),
            relativeFailedThresholdNegative: settings.get('relativeFailedThresholdNegative'),
            relativeUnstableThresholdPositive: settings.get('relativeUnstableThresholdPositive'),
            relativeUnstableThresholdNegative: settings.get('relativeUnstableThresholdNegative'),
            modePerformancePerTestCase: false,
            modeOfThreshold: settings.get('modeOfThreshold'),
            modeThroughput: settings.get('modeThroughput'),
            nthBuildNumber: settings.get('nthBuildNumber'),
            configType: settings.get('configType'),
            failBuildIfNoResultFile: settings.get('failBuildIfNoResultFile'),
            compareBuildPrevious: settings.get('compareBuildPrevious')
        )
        archiveResults(settings.get('archive'), pattern, settings.get('allowEmptyResults'))
    }
}

def publishCucumberReport(Map settings = [:]) {
    if(settings.active){
        def pattern = settings.get('pattern')
        def allowEmpty = settings.get('allowEmptyResults')

        cucumber(
            fileIncludePattern: "s4hana_pipeline/reports/*.json"
        )
        archiveResults(settings.get('archive'), "s4hana_pipeline/reports/e2e/**", allowEmpty)
    }
}

void touchFiles(pattern){
    echo "[${STEP_NAME}] update test results"
    def patternArray = pattern.split(',')
    for(def i = 0; i < patternArray.length; i++){
        sh "find . -wholename '${patternArray[i].trim()}' -exec touch {} \\;"
    }
}

def archiveResults(archive, pattern, allowEmpty) {
    if(archive){
        echo "[${STEP_NAME}] archive ${pattern}"
        archiveArtifacts artifacts: pattern, allowEmptyArchive: allowEmpty
    }
}

def prepare(parameters){
    // ensure tool maps are initialized correctly
    for(String tool : TOOLS){
        parameters[tool] = toMap(parameters[tool])
    }
    return parameters
}

def toMap(parameters){
    if(MapUtils.isMap(parameters))
        parameters.put('active', parameters.active == null?true:parameters.active)
    else if(Boolean.TRUE.equals(parameters))
        parameters = [active: true]
    else if(Boolean.FALSE.equals(parameters))
        parameters = [active: false]
    else
        parameters = [:]
    return parameters
}
