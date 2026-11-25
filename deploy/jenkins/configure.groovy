import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
def credentialId = 'catgal-github-readonly'
def domain = Domain.global()
def store = SystemCredentialsProvider.getInstance().getStore()
def stagedKey = new File(jenkins.rootDir, '.ssh/catgal-github')

// 插件就绪后导入本机生成的密钥，交由 Jenkins 加密保存。
if (!store.getCredentials(domain).any { it.id == credentialId }) {
    if (!stagedKey.isFile()) {
        return
    }
    def source = new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(stagedKey.getText('UTF-8'))
    def credential = new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, credentialId, 'git',
        source, '', 'Catgal GitHub read-only deploy key')
    if (!store.addCredentials(domain, credential)) {
        return
    }
    java.nio.file.Files.delete(stagedKey.toPath())
}

// 首次构建后，Jenkinsfile 中定义的 Poll SCM 生效。
if (jenkins.getItem('catgal-main') == null) {
    def scm = new GitSCM(
        [new UserRemoteConfig('git@github.com:shrikayuki/catgal.git', 'origin',
            '+refs/heads/main:refs/remotes/origin/main', credentialId)],
        [new BranchSpec('*/main')], false, [], null, null, [])
    def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
    definition.setLightweight(false)
    def job = jenkins.createProject(WorkflowJob, 'catgal-main')
    job.setDefinition(definition)
    job.save()
}
