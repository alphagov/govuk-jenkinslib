import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

/**
 * Tests of govuk.groovy.
 * @author <a href="mailto:VictorMartinezRubio@gmail.com">Victor Martinez</a>
 */
class GovukStepTests extends BasePipelineTest {
  static final String scriptName = 'vars/govuk.groovy'
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setVariable('env', env)

    helper.registerAllowedMethod('build', [Map.class], { 'OK' })
    helper.registerAllowedMethod('echo', [String.class], { s -> s })
    helper.registerAllowedMethod('sshagent', [Map.class, Closure.class], { map, body -> body() })
    helper.registerAllowedMethod('string', [Map.class], { s -> s })
    helper.registerAllowedMethod('sh', [String.class], { s -> s })
  }

  @Test
  void test_has_not_docker() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 1 })
    assertFalse(script.hasDockerfile())
    printCallStack()
  }

  @Test
  void test_has_docker() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 0 })
    assertTrue(script.hasDockerfile())
    printCallStack()
  }

  @Test
  void test_has_not_active_record_database() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { false })
    assertFalse(script.hasActiveRecordDatabase())
    printCallStack()
  }

  @Test
  void test_has_active_record_database() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { true })
    assertTrue(script.hasActiveRecordDatabase())
    printCallStack()
  }

  @Test
  void test_has_not_mongo_id_database() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { false })
    assertFalse(script.hasMongoidDatabase())
    printCallStack()
  }

  @Test
  void test_has_mongo_id_database() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { true })
    assertTrue(script.hasMongoidDatabase())
    printCallStack()
  }

  @Test
  void test_has_not_assets() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 1 })
    assertFalse(script.hasAssets())
    printCallStack()
  }

  @Test
  void test_has_assets() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 0 })
    assertTrue(script.hasAssets())
    printCallStack()
  }

  @Test
  void test_has_not_lint() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 1 })
    assertFalse(script.hasLint())
    printCallStack()
  }

  @Test
  void test_has_lint() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 0 })
    assertTrue(script.hasLint())
    printCallStack()
  }

  @Test
  void test_is_not_gem() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 1 })
    assertFalse(script.isGem())
    printCallStack()
  }

  @Test
  void test_is_gem() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('sh', [Map.class], { 0 })
    assertTrue(script.isGem())
    printCallStack()
  }

  @Test
  void test_is_not_rails() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { false })
    assertFalse(script.isRails())
    printCallStack()
  }

  @Test
  void test_is_rails() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('fileExists', [Map.class], { true })
    assertTrue(script.isRails())
    printCallStack()
  }

  @Test
  void test_deployIntegration_with_master_branch_is_executed() throws Exception {
    def script = loadScript(scriptName)
    script.deployIntegration('application', 'master', 'tag', 'deployTask')
    printCallStack()

    // Then it does trigger the integration-app-deploy job
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'build'
    }.any { call ->
      callArgsToString(call).trim().contains('job=integration-app-deploy')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_deployIntegration_with_another_branch_is_not_executed() throws Exception {
    def script = loadScript(scriptName)
    script.deployIntegration('application', 'foo', 'tag', 'deployTask')
    printCallStack()

    // Then it does not trigger the integration-app-deploy job
    assertFalse(helper.callStack.findAll { call ->
      call.methodName == 'build'
    }.any { call ->
      callArgsToString(call).trim().contains('job=integration-app-deploy')
    })
    assertJobStatusSuccess()
  }

}
