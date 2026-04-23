# Build-Time Packages

DaisyBase modules publish to GitHub Packages as Maven artifacts under the `dev.daisybase` group.

Common coordinates:

```kotlin
implementation("dev.daisybase:engine-api:0.1.0-SNAPSHOT")
implementation("dev.daisybase:jdbc:0.1.0-SNAPSHOT")
implementation("dev.daisybase:server:0.1.0-SNAPSHOT")
implementation("dev.daisybase:catalog:0.1.0-SNAPSHOT")
```

Consumers should add the GitHub Packages repository:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/DaisyQuest/DaisyBase")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_PACKAGES_TOKEN")
        }
    }
}
```

Use a token with `read:packages` access for local builds. The repository workflow uses `GITHUB_TOKEN` with `packages: write` to publish every Java module and upload the generated jars, WARs, and distributions as Actions artifacts.

Validate publication metadata locally without pushing packages:

```powershell
.\gradlew.bat clean build publishToMavenLocal
```
