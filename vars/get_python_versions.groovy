// Extract the python versions in the downstream repository's python_versions.json file
// and return them as a list
import groovy.json.JsonSlurper

def call(workspace) {
    def python_versions_string = readFile "${workspace}/python_versions.json"
    return new JsonSlurper().parseText(python_versions_string)
}