def call(Map args) {
    def deployer = new org.acme.Deployer(args.env)
    deployer.deploy(args.proxy)
}
