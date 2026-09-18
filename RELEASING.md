# Releasing

A release is one version in `gradle.properties`, and it reaches Maven Central as one bundle: the jar (`net.benelog.spidersense:spider-sense`) and the Gradle plugin with its marker, all signed, all at that version ([docs/build-tools.md](docs/build-tools.md#coordinates)).
A version on Central is permanent, so every step before the upload is a check that nothing is left behind.

## Every release

1. Set `version` in `gradle.properties`.
   It is the one place the build reads the version from; the plugin's included build reads it from there too, and the plugin's default for the jar it resolves is the same value.
2. Edit by hand every file that writes the version out: `README.md`, `docs/build-tools.md`, `manual/antora.yml` (`project-version`, which every manual page reads), `skills/spider-sense/SKILL.md` (`metadata.version` and the plugin line in the table) and `skills/spider-sense/references/running.md`.
   `git grep -n '<old version>'` names them.
3. Run `./gradlew build`, commit, and push to `main`.
4. Build the bundle, in two commands because nothing orders the plugin build's publish against the clean:
   ```bash
   export SIGNING_KEY="$(cat .secrets/signing-key.asc)" SIGNING_PASSWORD='…'
   ./gradlew cleanStagingRepository
   ./gradlew centralBundle
   ```
   `build/central-bundle-<version>.zip` holds `net/benelog/spidersense/spider-sense/`, `spider-sense-gradle-plugin/` and `net.benelog.spidersense.gradle.plugin/`, each jar and POM with its `.asc`.
   Without `SIGNING_KEY` the signing tasks are skipped and the bundle is unsigned, which Central rejects; that is the dry run.
5. Upload the zip on the Central Portal (<https://central.sonatype.com/publishing>, **Publish Component**), wait for `VALIDATED`, check the component list, press **Publish**.
   **Drop** discards it instead.
6. Tag the commit and push the tag:
   ```bash
   git tag v<version>
   git push origin v<version>
   ```
7. Cut the docs branch of the release:
   ```bash
   scripts/docs-branch.sh <version>
   ```
   It creates `docs/<version>` from the tag with the manual versioned as `<version>`, pushes it, and the Docs workflow publishes it at <https://spider-sense.benelog.net> as the latest version, at the site root.
   `main` goes on being built as `main (unreleased)`.
8. Publication takes up to half an hour.
   The release is done once <https://repo1.maven.org/maven2/net/benelog/spidersense/spider-sense/> lists the version; the Gradle Plugin Portal proxies Central, so `id 'net.benelog.spidersense' version '<version>'` resolves as soon as it does.

## Trying a version before it is released

`./gradlew publishToMavenLocal` puts the same three artifacts, unsigned, into `~/.m2`.
A project with `mavenLocal()` in `repositories` and in `pluginManagement.repositories` then resolves them like a release, which is the end-to-end test of the plugin outside this repository.

## One-time setup

The namespace `net.benelog` is verified on the Central Portal for the sibling project Spider Silk, and it covers `net.benelog.spidersense`.
The signing key, the portal token and where they live are described in Spider Silk's `RELEASING.md`; this repository uses the same key and the same account.
