def call() {
    writeFile file: 'base.mk', text: libraryResource('makefiles/base.mk')
}