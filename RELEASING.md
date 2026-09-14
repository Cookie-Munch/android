# Releasing (Android / Maven Central)

The library publishes to **Maven Central** as `net.cookiemunch:cookiemunch:<version>`
(group `net.cookiemunch`, artifact `cookiemunch`; version in
`cookiemunch/build.gradle.kts`).

## Prerequisites (TODO before first publish)

- [ ] **Sonatype Central / OSSRH account** with the `net.cookiemunch` namespace verified
      (DNS TXT proof of the `cookiemunch.net` domain).
- [ ] **GPG signing key**: generate (`gpg --gen-key`), publish the public key to a
      keyserver, and export the secret key for CI.
- [ ] Repository secrets: `OSSRH_USERNAME`, `OSSRH_PASSWORD` (Central portal token),
      `SIGNING_KEY` (ASCII-armored secret key), `SIGNING_PASSWORD`.

## Gradle publishing skeleton

Add the `maven-publish` + `signing` plugins to `cookiemunch/build.gradle.kts` and a
`publishing {}` block with the POM (name, description, licenses = MIT, developers, SCM
URL) plus a `sources`/`javadoc` artifact wiring. Then:

```bash
JAVA_HOME=/path/to/jdk17 \
  ./gradlew :cookiemunch:publishReleasePublicationToSonatypeRepository --no-daemon
```

(Or, for the older OSSRH staging flow, the `io.github.gradle-nexus.publish-plugin`
`publishToSonatype closeAndReleaseSonatypeStagingRepository` tasks.)

## Maven (mvn) alternative

If mirrored to a Maven POM project, `mvn -Prelease clean deploy` with the
`maven-gpg-plugin` + `central-publishing-maven-plugin` and the same OSSRH/GPG secrets in
`~/.m2/settings.xml`.

## CI publish trigger (skeleton)

```yaml
# .github/workflows/publish.yml
name: Publish to Maven Central
on:
  push:
    tags: ["v[0-9]+.[0-9]+.[0-9]+"]
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - run: ./gradlew :cookiemunch:publishReleasePublicationToSonatypeRepository --no-daemon
        env:
          OSSRH_USERNAME: ${{ secrets.OSSRH_USERNAME }}
          OSSRH_PASSWORD: ${{ secrets.OSSRH_PASSWORD }}
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
```
