#!/usr/bin/env groovy

def buildProject(Map options = [:]) {

  def jobName = JOB_NAME.split('/')[0]
  def repoName
  def defaultBranch

  // if you have a large repository you'll want to set shallowClone to only clone
  // a small portion of the repository and save time in build
  // example: `buildProject(shallowClone: true)` or for only a single branch `buildproject(shallowclone: env.BRANCH_NAME != "main")`
  def shallowClone = options.shallowClone != null ? options.shallowClone : false

  // if you have a shallow clone you'll be limited in how many commits are
  // available for a merge into the default branch. This will limit how many
  // commits can be in a pull request.
  // This option has no effect if shallowClone is not set
  // example: `buildproject(shallowclone: env.BRANCH_NAME != "main", mergeDepth: 50)`
  def mergeDepth = options.mergeDepth || 30

  if (options.repoName) {
    repoName = options.repoName
  } else {
    repoName = jobName
  }

  defaultBranch = isDefaultBranch(repoName, 'main') ? 'main' : 'master'

  def parameterDefinitions = [
    booleanParam(
      name: 'IS_SCHEMA_TEST',
      defaultValue: false,
      description: 'Identifies whether this build is being triggered to test a change to the content schemas'
    ),
    stringParam(
      name: 'SCHEMA_BRANCH',
      defaultValue: 'main',
      description: 'The branch of publishing api to use for schemas in test'
    ),
    stringParam(
      name: 'SCHEMA_COMMIT',
      defaultValue: 'invalid',
      description: 'The commit of publishing api that triggered this build, if it is a schema test'
    )
  ]

  if (options.extraParameters) {
    parameterDefinitions.addAll(options.extraParameters)
  }

  properties([
    buildDiscarder(
      logRotator(numToKeepStr: '30')
    ),
    [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
    [$class: 'ParametersDefinitionProperty', parameterDefinitions: parameterDefinitions],
  ])

  def defaultParameterValuesMap = [:]
  parameterDefinitions.each {
    // to handle params defined with the xxxParam(...) DSL instead of
    // [$class: ... ] style because we can't call .name / .defaultValue
    // on them directly
    if (it.class == org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable ||
        it.class == org.jenkinsci.plugins.workflow.cps.UninstantiatedDescribableWithInterpolation) {
      def mapVersionOfIt = it.toMap()
      defaultParameterValuesMap[mapVersionOfIt.name] = mapVersionOfIt.defaultValue
    } else {
      defaultParameterValuesMap[it.name] = it.defaultValue
    }
  }
  initializeParameters(defaultParameterValuesMap)

  try {
    if (!isAllowedBranchBuild(env.BRANCH_NAME)) {
      return
    }

    if (params.IS_SCHEMA_TEST) {
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job is building on Jenkins", 'PENDING', 'publishing-api')
    }

    if (options.cleanWorkspace != false) {
      stage("Clean workspace") {
        cleanWs()
      }
    }

    stage("Checkout") {
      checkoutFromGitHubWithSSH(repoName, [shallow: shallowClone])
    }

    stage("Merge ${defaultBranch}") {
      mergeIntoBranch(defaultBranch, [fetchBranch: shallowClone, mergeDepth: options.mergeDepth])
    }

    stage("Configure environment") {
      setEnvar("DISABLE_DATABASE_ENVIRONMENT_CHECK", "1")
      setEnvar("RAILS_ENV", "test")
      setEnvar("RACK_ENV", "test")
      setEnvar("DISPLAY", ":99")
    }

    publishingApiDependency(params.SCHEMA_BRANCH)

    if (isGem()) {
      // We've removed the ability to build RubyGems, if you really need it back see: https://github.com/alphagov/govuk-jenkinslib/pull/130
      error("GOV.UK Jenkins no longer builds RubyGems, please use GitHub Actions. See: https://docs.publishing.service.gov.uk/manual/test-and-build-a-project-with-github-actions.html#a-ruby-gem")
    }

    stage("bundle install") {
      bundleApp()
    }

    if (isRails() || options.brakeman) {
      stage("Security analysis") {
        runBrakemanSecurityScanner(repoName)
      }
    }

    if (options.yarnInstall != false && fileExists(file: "yarn.lock")) {
      stage("yarn install") {
        sh("yarn install --frozen-lockfile")
      }
    }

    if (options.beforeTest) {
      echo "Running pre-test tasks"
      options.beforeTest.call()
    }

    // Prevent a project's tests from running in parallel on the same node
    lock("$jobName-$NODE_NAME-test") {
      if (hasActiveRecordDatabase()) {
        stage("Set up the ActiveRecord database") {
          runRakeTask("db:reset")
        }
      }

      if (hasMongoidDatabase()) {
        stage("Set up the Mongoid database") {
          runRakeTask("db:drop")
          runRakeTask("db:setup")
        }
      }

      if (hasAssets()) {
        stage("Precompile assets") {
          precompileAssets()
        }
      }

      if (options.overrideTestTask) {
        echo "Running custom test task"
        options.overrideTestTask.call()
      } else {
        stage("Run tests") {
          runTests()
        }
      }

      if (fileExists(file: "coverage/rcov")) {
        stage("Ruby Code Coverage") {
          step([$class: "RcovPublisher", reportDir: "coverage/rcov"])
        }
      }

      if (fileExists("test/reports") ||
          fileExists("spec/reports") ||
          fileExists("features/reports")) {
        stage("junit reports") {
          junit(
            testResults: "test/reports/*.xml, spec/reports/*.xml, features/reports/*.xml",
            allowEmptyResults: true
          )
        }
      }
    }

    if (options.afterTest) {
      echo "Running post-test tasks"
      options.afterTest.call()
    }

    if (env.BRANCH_NAME == defaultBranch && !params.IS_SCHEMA_TEST) {
      stage("Push release tag") {
        pushTag(repoName, env.BRANCH_NAME, 'release_' + env.BUILD_NUMBER, defaultBranch)
      }

      if (!options.skipDeployToIntegration) {
        stage("Deploy to integration") {
          deployToIntegration(jobName, "release_${env.BUILD_NUMBER}", 'deploy')
        }
      }
    }

    if (params.IS_SCHEMA_TEST) {
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job succeeded on Jenkins", 'SUCCESS', 'publishing-api')
    }

  } catch (e) {
    currentBuild.result = "FAILED"
    step([$class: 'Mailer',
          notifyEveryUnstableBuild: true,
          recipients: 'govuk-ci-notifications@digital.cabinet-office.gov.uk',
          sendToIndividuals: true])

    if (params.IS_SCHEMA_TEST) {
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job failed on Jenkins", 'FAILED', 'publishing-api')
    }
    throw e
  }
}

/**
 * Run the brakeman security scanner against the current project
 *
 * @param repoName Name of the alphagov repository
 */
def runBrakemanSecurityScanner(repoName) {
  def brakemanExitCode = -1

  if (hasBrakeman()) {
    brakemanExitCode = sh(
      script: "bundle exec brakeman . --except CheckRenderInline",
      returnStatus: true
    )
  } else {
    // Install the brakeman gem and parse the output to retrieve the version we
    // just installed. We'll use that version to run the brakeman binary. We need
    // to do this because we can't just `gem install` the gem on Jenkins and want
    // to prevent having to add the gem to every Gemfile.
    def gemVersion = sh(
      script: "gem install --no-document -q --install-dir ${JENKINS_HOME}/manually-installed-gems brakeman | grep 'Successfully installed brakeman'",
      returnStdout: true
    ).replaceAll("Successfully installed ", "").trim()

    // Run brakeman's executable. If it finds security alerts it will return with
    // an exited code other than 0.
    brakemanExitCode = sh(
      script: "${JENKINS_HOME}/manually-installed-gems/gems/${gemVersion}/bin/brakeman . --except CheckRenderInline",
      returnStatus: true
    )
  }

  if (brakemanExitCode == 0) {
    setBuildStatus("security", getFullCommitHash(), "No security issues found", "SUCCESS", repoName)
  } else {
    setBuildStatus("security", getFullCommitHash(), "Brakeman found security issues", "FAILURE", repoName)
  }
}

/**
 * Cleanup anything left from previous test runs
 */
def cleanupGit() {
  echo 'Cleaning up git'
  sh('git clean -fdx')
}

/**
 * Check if the given branch is the default one.
 */
def isDefaultBranch(String repository, String branchName) {
  sshagent(['govuk-ci-ssh-key']) {
    def lines = sh(
      script: "git ls-remote git@github.com:alphagov/${repository}.git",
      returnStdout: true
    ).trim().split('\n')

    def headCommitish = lines[0].split()[0]

    for (line in lines) {
      def bits = line.split()
      if (bits[0] == headCommitish) {
        if (bits[1] == "refs/heads/${branchName}") {
          return true
        }
      }
    }

    return false
  }
}

/**
 * Checkout repo using SSH key
 */
def checkoutFromGitHubWithSSH(String repository, Map options = [:]) {
  def defaultOptions = [
    branch: null,
    changelog: true,
    location: null,
    shallow: false,
    org: "alphagov",
    poll: true,
    host: "github.com"
  ]
  options = defaultOptions << options

  def branches
  if (options.branch) {
    branches = [[ name: options.branch ]]
  } else {
    branches = scm.branches
  }

  def extensions = [
    [
      $class: "CleanCheckout",
    ],
    [
      $class: 'CloneOption',
      shallow: options.shallow,
      noTags: options.shallow,
    ]
  ]

  if(options.directory) {
    extensions << [
      $class: "RelativeTargetDirectory",
      relativeTargetDir: options.directory
    ]
  }

  checkout([
    changelog: options.changelog,
    poll: options.poll,
    scm: [
      $class: 'GitSCM',
      branches: branches,
      doGenerateSubmoduleConfigurations: false,
      extensions: extensions,
      submoduleCfg: [],
      userRemoteConfigs: [[
        credentialsId: 'govuk-ci-ssh-key',
        url: "git@${options.host}:${options.org}/${repository}.git"
      ]]
    ]
  ])
}

/**
 * Checkout a dependent repo.
 * This function acts as a wrapper around checkoutFromGitHubWithSSH with
 * options tailored towards the needs of a secondary repo cloned as part of a
 * pipeline job
 *
 * It can accept an optional closure that is run within the directory that has
 * been cloned
 */
def checkoutDependent(String repository, options = [:], Closure closure = null) {
  def defaultOptions = [
    branch: "main",
    shallow: true,
    changelog: false,
    directory: "tmp/${repository}",
    poll: false
  ]
  options = defaultOptions << options

  stage("Cloning ${repository}") {
    checkoutFromGitHubWithSSH(repository, options)
  }

  if (closure) {
    dir(options.directory) {
      closure.call()
    }
  }
}


/**
 * Check if the git HEAD is ahead of a particular branch
 * This will be false for development branches and true for release branches,
 * and the default branch.
 */
def isCurrentCommitOnBranch(String branch) {
  sh(
    script: "git rev-list origin/${branch} | grep \$(git rev-parse HEAD)",
    returnStatus: true
  ) == 0
}

/**
 * Check whether there is a git branch named release
 * This test is useful for determining whether we should update this branch or
 * not
 */
def releaseBranchExists() {
  sshagent(["govuk-ci-ssh-key"]) {
    sh(
      script: "git ls-remote --exit-code --refs origin release",
      returnStatus: true
    ) == 0
  }
}

/**
 * Try to merge a branch into the current branch
 *
 * This will abort if it doesn't exit cleanly (ie there are conflicts), and
 * will be a noop if the current branch is the specified branch or is in the
 * history of it, e.g. a previously-merged dev branch or the deployed-to-production
 * branch.
 */
def mergeIntoBranch(String branch, Map options = [:]) {
  def defaultOptions = [
    fetchBranch: false,
    mergeDepth: 30
  ]
  options = defaultOptions << options

  if (isCurrentCommitOnBranch(branch)) {
    echo "Current commit is on ${branch}, so building this branch without " +
      "merging in ${branch} branch."
  } else {
    echo "Current commit is not on ${branch}, so attempting merge of ${branch} " +
      "branch before proceeding with build"

    if (options.fetchBranch) {
      sshagent(['govuk-ci-ssh-key']) {
        sh("git fetch --no-tags --depth=${options.mergeDepth} origin " +
           "+refs/heads/${branch}:refs/remotes/origin/${branch} " +
           "refs/heads/${env.BRANCH_NAME}:refs/remotes/origin/${env.BRANCH_NAME}")
      }
    }
    sh("git merge --no-commit origin/${branch} || git merge --abort")
  }

}

/**
 * Sets environment variable
 *
 * Cannot iterate over maps in Jenkins2 currently
 *
 * Note: for scope-related reasons the code in here is inlined directly
 * in the initializeParameters method below, if you change our version
 * you should update it there too.
 *
 * @param key
 * @param value
 */
def setEnvar(String key, String value) {
  echo "Setting environment variable ${key}"
  env."${key}" = value
}

/**
 * Ensure missing build parameters are set to their default values
 *
 * This fixes an issue where the parameters are missing on the very first
 * pipeline build of a new branch (JENKINS-40574). They are set correctly on
 * every subsequent build, whether it is triggered automatically by a branch
 * push or manually by a Jenkins user.
 *
 * This doesn't use setEnvar because for some scope-related reason we couldn't
 * work out, first builds would fail because it couldn't find setEnvar. We
 * inline the code instead.
 *
 * @param defaultBuildParams map of build parameter names to default values
 */
def initializeParameters(Map<String, String> defaultBuildParams) {
  for (param in defaultBuildParams) {
    if (env."${param.key}" == null) {
      echo "Setting environment variable ${param.key}"
      env."${param.key}" = param.value
    }
  }
}

/**
 * Check whether the Jenkins build should be run for the current branch
 *
 * Builds can be run if it's against a regular branch build or if it is
 * being run to test the content schema.
 *
 * Jenkinsfiles should run this check if the project is used to test updates
 * to the content schema. Other projects should be configured in Puppet to
 * exclude builds of non-dev branches, so this check is unnecessary.
 */
def isAllowedBranchBuild(
  String currentBranchName,
  String deployedBranchName = "deployed-to-production") {

  if (currentBranchName == deployedBranchName) {
    if (params.IS_SCHEMA_TEST) {
      echo "Branch is '${deployedBranchName}' and this is a schema test " +
        "build. Proceeding with build."
      return true
    } else {
      echo "Branch is '${deployedBranchName}', but this is not marked as " +
        "a schema test build. '${deployedBranchName}' should only be " +
        "built as part of a schema test, so this build will stop here."
      return false
    }
  }

  echo "Branch is '${currentBranchName}', so this is a regular dev branch " +
    "build. Proceeding with build."
  return true
}

def getGitCommit() {
  return sh(
    script: 'git rev-parse --short HEAD',
    returnStdout: true
  ).trim()
}

/**
 * Sets the current git commit in the env. Used by the linter
 */
def setEnvGitCommit() {
  env.GIT_COMMIT = getGitCommit()
}


/**
 * Precompiles assets
 */
def precompileAssets() {
  echo 'Precompiling the assets'
  sh('RAILS_ENV=test SECRET_KEY_BASE=1 GOVUK_WEBSITE_ROOT=http://www.test.gov.uk GOVUK_APP_DOMAIN=test.gov.uk GOVUK_APP_DOMAIN_EXTERNAL=test.gov.uk GOVUK_ASSET_ROOT=https://static.test.gov.uk bundle exec rake assets:clobber assets:precompile')
}

/**
 * Clone publishing api containing content-schemas dependency for contract tests
 */
def publishingApiDependency(String schemaGitCommit = 'main') {
  checkoutDependent("publishing-api", [ branch: schemaGitCommit ]) {
    setEnvar("GOVUK_CONTENT_SCHEMAS_PATH", "${pwd()}/content_schemas" )
  }
}

/**
 * Sets up test database
 */
def setupDb() {
  echo 'Setting up database'
  sh('RAILS_ENV=test bundle exec rake db:environment:set db:drop db:create db:schema:load')
}

/**
 * Get the number of available processors
 */
def availableProcessors() {
  Runtime.getRuntime().availableProcessors()
}

/**
 * Bundles all the gems in deployment mode
 */
def bundleApp() {
  echo 'Bundling'
  lock ("bundle_install-$NODE_NAME") {
    if (bundlerVersionAtLeast(2, 1)) {
      sh("bundle config set --local path '${JENKINS_HOME}/bundles'")
      sh("bundle config set --local deployment 'true'")
      sh("bundle config set --local without 'development'")
      sh("bundle install --jobs=${availableProcessors()}")
    } else {
      sh("bundle install --jobs=${availableProcessors()} --path ${JENKINS_HOME}/bundles --deployment --without development")
    }
  }
}

def bundlerVersionAtLeast(int expectedMajorVersion, int expectedMinorVersion = 0) {
  def bundlerVersion = sh(
    script: "bundle version | cut -d ' ' -f3",
    returnStdout: true
  ).trim()

  def actualMajorVersion = bundlerVersion.tokenize('.')[0] as int
  def actualMinorVersion = bundlerVersion.tokenize('.')[1] as int

  (actualMajorVersion > expectedMajorVersion) ||
    (actualMajorVersion == expectedMajorVersion && actualMinorVersion >= expectedMinorVersion)
}

/**
 * Runs the tests
 *
 * @param test_task Optional test_task instead of 'default'
 */
def runTests(String test_task = 'default') {
  sh("bundle exec rake ${test_task}")
}

/**
 * Runs rake task
 *
 * @param task Task to run
 */
def runRakeTask(String rake_task) {
  echo "Running ${rake_task} task"
  sh("bundle exec rake ${rake_task}")
}

/**
 * Push tags to GitHub repository
 *
 * @param repository GitHub repository
 * @param branch Branch name
 * @param tag Tag name
 */
def pushTag(String repository, String branch, String tag, String defaultBranch = 'master') {
  if (branch == defaultBranch){
    echo 'Pushing tag'
    sshagent(['govuk-ci-ssh-key']) {
      sh("git tag -a ${tag} -m 'Jenkinsfile tagging with ${tag}'")
      echo "Tagging alphagov/${repository} ${defaultBranch} branch -> ${tag}"
      sh("git push git@github.com:alphagov/${repository}.git ${tag}")

      // TODO: pushTag would be better if it only did exactly that,
      // but lots of Jenkinsfiles expect it to also update the release
      // branch. There are cases where release branches are not used
      // (e.g. repositories containing Ruby gems). For now, just check
      // if the release branch exists on the remote, and only push to it
      // if it does.
      if (releaseBranchExists()) {
        echo "Updating alphagov/${repository} release branch"
        sh("git push git@github.com:alphagov/${repository}.git HEAD:refs/heads/release")
      }
    }
  } else {
    echo 'No tagging on branch'
  }
}

/**
 * Deploy application on the Integration environment
 *
 * @param application ID of the application, which should match the ID
 *        configured in puppet and which is usually the same as the repository
 *        name
 * @param tag Tag to deploy
 * @param deployTask Deploy task (deploy, deploy:migrations or deploy:setup)
 */
def deployToIntegration(String application, String tag, String deployTask) {
  build job: 'Deploy_App_Downstream', parameters: [
    string(name: 'TARGET_APPLICATION', value: application),
    string(name: 'TAG', value: tag),
    string(name: 'DEPLOY_TASK', value: deployTask)
  ], wait: false
}

/**
 * Does this project use Rails-style assets?
 */
def hasAssets() {
  sh(script: "test -d app/assets", returnStatus: true) == 0
}

/**
 * Does this project use Rubocop?
 */
def hasRubocop() {
  sh(script: "grep 'rubocop' Gemfile.lock", returnStatus: true) == 0
}

/**
 * Does this project use Brakeman?
 */
def hasBrakeman() {
  sh(script: "grep 'brakeman' Gemfile.lock", returnStatus: true) == 0
}

/**
 * Is this a Ruby gem?
 *
 * Determined by checking the presence of a `.gemspec` file
 */
def isGem() {
  sh(script: "ls | grep gemspec", returnStatus: true) == 0
}

/**
 * Is this a Rails app?
 *
 * Determined by checking if bin/rails exists.
 */
def isRails() {
  fileExists(file: "bin/rails")
}

/**
 * Does this project use a Rails-style database?
 *
 * Determined by checking the presence of a `database.yml` file
 */
def hasActiveRecordDatabase() {
  fileExists(file: "config/database.yml")
}

/**
 * Does this project use a Mongoid-style database?
 *
 * Determined by checking the presence of a `mongoid.yml` file.
 */
def hasMongoidDatabase() {
  fileExists(file: "config/mongoid.yml")
}

/*
 * Upload the artefact at artefact_path to the given s3_path. Uses the
 * govuk-s3-artefact-creds for access.
 */
def uploadArtefactToS3(artefact_path, s3_path){
  withCredentials([[$class: 'UsernamePasswordMultiBinding',
                    credentialsId: 'govuk-s3-artefact-creds',
                    usernameVariable: 'AWS_ACCESS_KEY_ID',
                    passwordVariable: 'AWS_SECRET_ACCESS_KEY']]){
    sh 's3cmd --region eu-west-1 --acl-public --access_key $AWS_ACCESS_KEY_ID --secret_key $AWS_SECRET_ACCESS_KEY put ' + "$artefact_path $s3_path"
  }
}

/**
 * Manually set build status in GitHub.
 *
 * Useful for downstream builds that want to report on the upstream PR.
 *
 * @param jobName Name of the jenkins job being built
 * @param commit SHA of the triggering commit on publishing api
 * @param message The message to report
 * @param state The build state: one of PENDING, SUCCESS, FAILED
 * @param repoName The alphagov repository
 */
def setBuildStatus(jobName, commit, message, state, repoName) {
  step([
      $class: "GitHubCommitStatusSetter",
      commitShaSource: [$class: "ManuallyEnteredShaSource", sha: commit],
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/alphagov/${repoName}"],
      contextSource: [$class: "ManuallyEnteredCommitContextSource", context: "continuous-integration/jenkins/${jobName}"],
      errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
}

def getFullCommitHash() {
  return sh(
    script: "git rev-parse HEAD",
    returnStdout: true
  ).trim()
}

/*
 * This is a method to test that the external library loading
 * works as expect
 */
def pipelineTest() {
  sh("echo 'If you see this I am working as expected'")
}

return this;
