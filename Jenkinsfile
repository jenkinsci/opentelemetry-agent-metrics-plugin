#!/usr/bin/env groovy

// buildPlugin cannot pass system properties to Maven; this variable reaches the Surefire JVMs and makes the stand-in
// tests fail instead of skip when the Windows launcher toolchain is unusable (see StandIns.REQUIRE_ENV).
withEnv(['ONMONIT_REQUIRE_STAND_INS=true']) {
    /* `buildPlugin` step provided by: https://github.com/jenkins-infra/pipeline-library */
    buildPlugin(
        useContainerAgent: true, // Set to `false` if you need to use Docker for containerized tests
        configurations: [
            [ platform: 'linux', jdk: 21],
            [ platform: 'windows', jdk: 17 ],
        ]
    )
}
