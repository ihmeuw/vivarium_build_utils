// Extract the python versions in the downstream repository's python_versions.json file
// and return them as a list
import groovy.json.JsonSlurper

def call() {
    def python_versions_file = new File("python_versions.json")
    return new JsonSlurper().parse(python_versions_file)
}