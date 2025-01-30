// Extract the python versions in the downstream repository's python_versions.json file
// and return them as a list
def call() {
    def python_versions = []
    def python_versions_file = readFile("python_versions.json")
    def python_versions_json = new JsonSlurper().parseText(python_versions_file)
    python_versions_json.each { version ->
        python_versions.add(version)
    }
    return python_versions
}