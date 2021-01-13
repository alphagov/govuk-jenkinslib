#!/usr/bin/env groovy

def buildProject(Map options = [:]) {

  def jobName = JOB_NAME.split('/')[0]
  def repoName
  def gemName

  if (options.repoName) {
    repoName = options.repoName
  } else {
    repoName = jobName
  }

  if (options.gemName) {
    gemName = options.gemName
  } else {
    gemName = repoName
  }

  def parameterDefinitions = [
    booleanParam(
      name: 'IS_SCHEMA_TEST',
      defaultValue: false,
      description: 'Identifies whether this build is being triggered to test a change to the content schemas'
    ),
    booleanParam(
      name: 'PUSH_TO_GCR',
      defaultValue: false,
      description: '--TESTING ONLY-- Whether to push the docker image to Google Container Registry.'
    ),
    booleanParam(
      name: 'RUN_DOCKER_TASKS',
      defaultValue: true,
      description: 'Whether to build and push the Docker image, if a Dockerfile exists.'
    ),
    stringParam(
      name: 'SCHEMA_BRANCH',
      defaultValue: 'deployed-to-production',
      description: 'The branch of govuk-content-schemas to test against'
    ),
    stringParam(
      name: 'SCHEMA_COMMIT',
      defaultValue: 'invalid',
      description: 'The commit of govuk-content-schemas that triggered this build, if it is a schema test'
    )
  ]

  if (options.publishingE2ETests == true && env.PUBLISHING_E2E_TESTS_BRANCH == null) {
    parameterDefinitions << stringParam(
      name: "PUBLISHING_E2E_TESTS_BRANCH",
      defaultValue: "test-against",
      description: "The branch of publishing-e2e-tests to test against"
    )
  }

  if (options.extraParameters) {
    parameterDefinitions.addAll(options.extraParameters)
  }

  properties([
    buildDiscarder(
      logRotator(numToKeepStr: '50')
    ),
    [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
    [$class: 'ParametersDefinitionProperty', parameterDefinitions: parameterDefinitions],
  ])

  def defaultParameterValuesMap = [:]
  parameterDefinitions.each {
    // to handle params defined with the xxxParam(...) DSL instead of
    // [$class: ... ] style because we can't call .name / .defaultValue
    // on them directly
    if (it.class == org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable) {
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
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job is building on Jenkins", 'PENDING', 'govuk-content-schemas')
    }

    stage("Checkout") {
      checkoutFromGitHubWithSSH(repoName)
    }

    stage("Merge master") {
      mergeMasterBranch()
    }

    stage("Configure environment") {
      setEnvar("DISABLE_DATABASE_ENVIRONMENT_CHECK", "1")
      setEnvar("RAILS_ENV", "test")
      setEnvar("RACK_ENV", "test")
      setEnvar("DISPLAY", ":99")
    }

    if (hasDockerfile() && params.RUN_DOCKER_TASKS && !params.IS_SCHEMA_TEST) {
      parallel (
        "build" : { nonDockerBuildTasks(options, jobName, repoName) },
        "docker" : { dockerBuildTasks(options, jobName) }
      )
    } else {
      nonDockerBuildTasks(options, jobName, repoName)
    }

    if (env.BRANCH_NAME == "master" && !params.IS_SCHEMA_TEST) {
      if (isGem()) {
        stage("Publish Gem to Rubygems") {
          publishGem(gemName, repoName, env.BRANCH_NAME)
        }
      } else {
        stage("Push release tag") {
          pushTag(repoName, env.BRANCH_NAME, 'release_' + env.BUILD_NUMBER)
        }

        if (hasDockerfile() && params.RUN_DOCKER_TASKS) {
          stage("Tag Docker image") {
            dockerTagMasterBranch(jobName, env.BRANCH_NAME, env.BUILD_NUMBER)
          }
        }

        if (!options.skipDeployToIntegration) {
          stage("Deploy to integration") {
            deployIntegration(jobName, env.BRANCH_NAME, "release_${env.BUILD_NUMBER}", 'deploy')
          }
        }
      }
    }
    if (params.IS_SCHEMA_TEST) {
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job succeeded on Jenkins", 'SUCCESS', 'govuk-content-schemas')
    }

  } catch (e) {
    currentBuild.result = "FAILED"
    step([$class: 'Mailer',
          notifyEveryUnstableBuild: true,
          recipients: 'govuk-ci-notifications@digital.cabinet-office.gov.uk',
          sendToIndividuals: true])
    if (params.IS_SCHEMA_TEST) {
      setBuildStatus(jobName, params.SCHEMA_COMMIT, "Downstream ${jobName} job failed on Jenkins", 'FAILED', 'govuk-content-schemas')
    }
    throw e
  }
}

def nonDockerBuildTasks(options, jobName, repoName) {
  contentSchemaDependency(params.SCHEMA_BRANCH)

  stage("bundle install") {
    isGem() ? bundleGem() : bundleApp()
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
      if (isGem()) {
        def extraRubyVersions = options.extraRubyVersions == null ? [] : options.extraRubyVersions
        testGemWithAllRubies(extraRubyVersions)
      } else {
        stage("Run tests") {
          runTests()
        }
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

  if (options.publishingE2ETests == true && !params.IS_SCHEMA_TEST) {
    stage("End-to-end tests") {
      if ( env.PUBLISHING_E2E_TESTS_APP_PARAM == null ) {
        appCommitishName = jobName.replace("-", "_").toUpperCase() + "_COMMITISH"
      } else {
        appCommitishName = env.PUBLISHING_E2E_TESTS_APP_PARAM
      }
      if ( env.PUBLISHING_E2E_TESTS_BRANCH == null ) {
        testBranch = "test-against"
      } else {
        testBranch = env.PUBLISHING_E2E_TESTS_BRANCH
      }
      if ( env.PUBLISHING_E2E_TESTS_COMMAND == null ) {
        testCommand = "test"
      } else {
        testCommand = env.PUBLISHING_E2E_TESTS_COMMAND
      }
      runPublishingE2ETests(appCommitishName, testBranch, repoName, testCommand)
    }
  }

  if (options.afterTest) {
    echo "Running post-test tasks"
    options.afterTest.call()
  }
}

def dockerBuildTasks(options, jobName) {
  stage("Build Docker image") {
    buildDockerImage(jobName, env.BRANCH_NAME, true)
  }

  if (!(env.BRANCH_NAME ==~ /^deployed-to/)) {
    stage("Push Docker image") {
      pushDockerImage(jobName, env.BRANCH_NAME)

      if (params.PUSH_TO_GCR) {
        pushDockerImageToGCR(jobName, env.BRANCH_NAME)
      }
    }
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
 * Checkout repo using SSH key
 */
def checkoutFromGitHubWithSSH(String repository, Map options = [:]) {
  def defaultOptions = [
    branch: null,
    changelog: true,
    location: null,
    shallow: env.BRANCH_NAME != "master",
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
    branch: "master",
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
 * Check if the git HEAD is ahead of master.
 * This will be false for development branches and true for release branches,
 * and master itself.
 */
def isCurrentCommitOnMaster() {
  sh(
    script: 'git rev-list origin/master | grep $(git rev-parse HEAD)',
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
 * Try to merge master into the current branch
 *
 * This will abort if it doesn't exit cleanly (ie there are conflicts), and
 * will be a noop if the current branch is master or is in the history for
 * master, e.g. a previously-merged dev branch or the deployed-to-production
 * branch.
 */
def mergeMasterBranch() {
  if (isCurrentCommitOnMaster()) {
    echo "Current commit is on master, so building this branch without " +
      "merging in master branch."
  } else {
    echo "Current commit is not on master, so attempting merge of master " +
      "branch before proceeding with build"

    sshagent(['govuk-ci-ssh-key']) {
      sh("git fetch --no-tags --depth=30 origin " +
         "+refs/heads/master:refs/remotes/origin/master " +
         "refs/heads/${env.BRANCH_NAME}:refs/remotes/origin/${env.BRANCH_NAME}")
    }
    sh('git merge --no-commit origin/master || git merge --abort')
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
 * Clone govuk-content-schemas dependency for contract tests
 */
def contentSchemaDependency(String schemaGitCommit = 'deployed-to-production') {
  checkoutDependent("govuk-content-schemas", [ branch: schemaGitCommit ]) {
    setEnvar("GOVUK_CONTENT_SCHEMAS_PATH", pwd())
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
    sh("bundle install --jobs=${availableProcessors()} --path ${JENKINS_HOME}/bundles --deployment --without development")
  }
}

/**
 * Bundles all the gems
 */
def bundleGem() {
  echo 'Bundling'
  lock ("bundle_install-$NODE_NAME") {
    sh("bundle install --jobs=${availableProcessors()} --path ${JENKINS_HOME}/bundles")
  }
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
 * Runs the tests with all the Ruby versions that are currently supported.
 *
 * Adds a Jenkins stage for each Ruby version, so do not call this from within
 * a stage.
 *
 * @param extraRubyVersions Optional Ruby versions to run the tests against in
 * addition to the versions currently supported by all GOV.UK applications
 */
def testGemWithAllRubies(extraRubyVersions = []) {
  def rubyVersions = ["2.6", "2.7"]

  rubyVersions.addAll(extraRubyVersions)

  for (rubyVersion in rubyVersions) {
    stage("Test with ruby $rubyVersion") {
      sh "rm -f Gemfile.lock"
      setEnvar("RBENV_VERSION", rubyVersion)
      bundleGem()

      runTests()
    }
  }
  sh "unset RBENV_VERSION"
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
def pushTag(String repository, String branch, String tag) {
  if (branch == 'master'){
    echo 'Pushing tag'
    sshagent(['govuk-ci-ssh-key']) {
      sh("git tag -a ${tag} -m 'Jenkinsfile tagging with ${tag}'")
      echo "Tagging alphagov/${repository} master branch -> ${tag}"
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
 * @param branch Branch name
 * @param tag Tag to deploy
 * @param deployTask Deploy task (deploy, deploy:migrations or deploy:setup)
 */
def deployIntegration(String application, String branch, String tag, String deployTask) {
  if (branch == 'master') {
    build job: 'Deploy_App_Downstream', parameters: [
      string(name: 'TARGET_APPLICATION', value: application),
      string(name: 'TAG', value: tag),
      string(name: 'DEPLOY_TASK', value: deployTask)
    ], wait: false
  }
}

/**
 * Publish a gem to rubygems.org
 *
 * @param name Name of the gem. This should match the name of the gemspec file.
 * @param repository Name of the repository. This is used to add a git tag for the release.
 * @param branch Branch name being published. Only publishes if this is 'master'
 */
def publishGem(String name, String repository, String branch) {
  if (branch != 'master') {
    return
  }

  def version = sh(
    script: /ruby -e "puts eval(File.read('${name}.gemspec'), TOPLEVEL_BINDING).version.to_s"/,
    returnStdout: true
  ).trim()

  sshagent(['govuk-ci-ssh-key']) {
    echo "Fetching remote tags"
    sh("git fetch --tags")
  }

  def escapedVersion = version.replaceAll(/\./, /\\\\./)
  def versionAlreadyPublished = sh(
    script: /gem list ^${name}\$ --remote --all --quiet | grep [^0-9\\.]${escapedVersion}[^0-9\\.]/,
    returnStatus: true
  ) == 0

  if (versionAlreadyPublished) {
    echo "Version ${version} has already been published to rubygems.org. Skipping publication."
  } else {
    echo('Publishing gem')
    sh("gem build ${name}.gemspec")
    sh("gem push ${name}-${version}.gem")
  }

  def taggedReleaseExists = false

  sshagent(['govuk-ci-ssh-key']) {
    taggedReleaseExists = sh(
      script: "git ls-remote --exit-code --tags origin v${version}",
      returnStatus: true
    ) == 0
  }

  if (taggedReleaseExists) {
    echo "Version ${version} has already been tagged on GitHub. Skipping publication."
  } else {
    echo('Pushing tag')
    pushTag(repository, branch, 'v' + version)
  }
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

def validateDockerFileRubyVersion() {
  if (fileExists(file: ".ruby-version")) {
    def rubyVersion = readFile(file: ".ruby-version")
    // Remove any patch information from the ruby version. 2.0.0-p648 -> 2.0.0
    rubyVersion = rubyVersion.trim().split("-")[0]

    // The Dockerfile base image version can be optionally suffixed with a - followed by a variant
    // e.g. ruby:2.4.2-slim
    def hasMatchingVersions = sh(script: "egrep \"FROM ruby:${rubyVersion}(\$|-)\" Dockerfile", returnStatus: true) == 0
    if (!hasMatchingVersions) {
      def baseImageDefinition = sh(script: "egrep \"FROM \" Dockerfile", returnStdout: true).trim()
      error("Dockerfile uses base image \"${baseImageDefinition}\", this mismatches .ruby-version \"${rubyVersion}\"")
    }
  }
}

def hasDockerfile() {
  sh(script: "test -e Dockerfile", returnStatus: true) == 0
}

def buildDockerImage(imageName, tagName, quiet = false) {
  validateDockerFileRubyVersion()
  tagName = safeDockerTag(tagName)
  args = "${quiet ? '--quiet' : ''} --pull ."
  docker.build("govuk/${imageName}:${tagName}", args)
}

/**
 */
def dockerTagMasterBranch(jobName, branchName, buildNumber) {
  dockerTag = "release_${buildNumber}"
  pushDockerImage(jobName, branchName, dockerTag)

  if (releaseBranchExists()) {
    pushDockerImage(jobName, branchName, "release")
  }
}

/*
 * Push the image to the govuk docker hub and tag it. If `asTag` is set then
 * the image is also tagged with that value otherwise the `tagName` is used.
 */
def pushDockerImage(imageName, tagName, asTag = null) {
  tagName = safeDockerTag(tagName)
  docker.withRegistry('https://index.docker.io/v1/', 'govukci-docker-hub') {
    docker.image("govuk/${imageName}:${tagName}").push(asTag ?: tagName)
  }
}

def pushDockerImageToGCR(imageName, tagName) {
  tagName = safeDockerTag(tagName)
  gcrName = "gcr.io/govuk-test/${imageName}"
  docker.build(gcrName)

  withCredentials([file(credentialsId: 'govuk-test', variable: 'GCR_CRED_FILE')]) {
    // We don't want to interpolate this command as GCR_CRED_FILE is set as an
    // environment variable in bash.
    command = 'gcloud auth activate-service-account --key-file "$GCR_CRED_FILE"'
    sh command
    // We do want to interpolate this command to get the value of gcrName
    command = "gcloud docker -- push ${gcrName}"
    sh command
    // Add the tag, again this needs to be interpolated
    command = "gcloud container images add-tag ${gcrName} ${gcrName}:${tagName}"
    sh command
  }
}

def safeDockerTag(tagName) {
  // A valid tag is:
  //   ascii, uppercase, lowercase, digits, underscore, dash, period,
  //   128 chars, can't start with dash or period
  // See: https://docs.docker.com/engine/reference/commandline/tag/#extended-description
  return tagName.replaceAll(/[^a-zA-Z0-9-_.]|^[-.]/, "_").take(128)
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
    sh "s3cmd --region eu-west-1 --acl-public --access_key $AWS_ACCESS_KEY_ID --secret_key $AWS_SECRET_ACCESS_KEY put $artefact_path $s3_path"
  }
}

/**
 * Manually set build status in GitHub.
 *
 * Useful for downstream builds that want to report on the upstream PR.
 *
 * @param jobName Name of the jenkins job being built
 * @param commit SHA of the triggering commit on govuk-content-schemas
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

def runPublishingE2ETests(appCommitishName, testBranch, repo, testCommand = "test") {
  fullCommitHash = getFullCommitHash()
  build(
    job: "publishing-e2e-tests/${testBranch}",
    parameters: [
      [$class: "StringParameterValue",
       name: appCommitishName,
       value: fullCommitHash],
      [$class: "StringParameterValue",
       name: "TEST_COMMAND",
       value: testCommand],
      [$class: "StringParameterValue",
       name: "ORIGIN_REPO",
       value: repo],
      [$class: "StringParameterValue",
       name: "ORIGIN_COMMIT",
       value: fullCommitHash]
    ],
    wait: false,
  )
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
