def call(Map args) {
    sh "echo deploying ${args.env}";
    println args.env
}
