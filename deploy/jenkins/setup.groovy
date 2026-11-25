import jenkins.model.Jenkins

def controller = Jenkins.get()
def required = ['git', 'credentials', 'ssh-credentials', 'workflow-job', 'workflow-cps']
if (required.every { controller.pluginManager.getPlugin(it)?.isActive() }) {
    new GroovyShell(controller.pluginManager.uberClassLoader)
        .evaluate(new File('/opt/catgal-jenkins/configure.groovy'))
}
