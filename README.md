## GOV.UK Jenkinslib

A library for setting up CI environments in GOV.UK.

The library will need to be loaded into a Jenkins instance before being able to use it. This
will depend on Jenkins [being configured to load it](https://jenkins.io/doc/book/pipeline/shared-libraries/#global-shared-libraries).

To load the library, use the `library` function:

`library("govuk")`

To specify a different branch for testing:

`library("govuk@my-new-branch")`

## Setting up CI on GOV.UK

For most Ruby projects, the following `Jenkinsfile` is sufficient:

```groovy
#!/usr/bin/env groovy

library("govuk")

node {
  govuk.buildProject()
}
```

This will set up dependencies, run the tests and report back to GitHub.

For applications: the `master` branch of applications will be deployed to integration

For gems: if the version has changed, the latest version will be released to rubygems.org

## Exceptions


If you need to run tests using a command other than the default rake task
you can do this by specifying the `overrideTestTask` option:

```groovy
#!/usr/bin/env groovy

library("govuk")

node {
  govuk.buildProject(overrideTestTask: {
    stage("Run custom tests") {
      govuk.runRakeTask("super-special-tests")
    }
  })
}
```

Parameter | Description | Default
--- | --- | ---
afterTest | A closure containing commands to run after the test stage, such as report publishing |
beforeTest | A closure containing commands to run before the test stage, such as environment variable configuration
brakeman | Whether or not to run the Brakeman security scanner | `true` if a Rails app, otherwise `false`
extraParameters | Provide details here of any extra parameters that can be used to configure this build.  See: https://jenkins.io/doc/pipeline/steps/workflow-multibranch/#code-properties-code-set-job-properties for details on the format and structure of these extra parameters. |
extraRubyVersions | Ruby versions to run the tests against in addition to the versions currently supported by all GOV.UK applications. Only applies to gems because they may be used in projects with different Ruby versions. | `[]`
gemName | If publishing a Rubygem, you can specify the Gem name. | Repository name
overrideTestTask | A closure containing commands to run to test the project. This will run instead of the default `bundle exec rake` |
publishingE2ETests | Whether or not to run the Publishing end-to-end tests. | `false`
skipDeployToIntegration | Whether or not to skip the "Deploy to integration" stage | `false`
yarnInstall | Whether or not to install Yarn dependencies if a yarn.lock file is found | `true`
