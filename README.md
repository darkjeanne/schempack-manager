# SchemPack Manager
Make your Mindustry schematic folder a GitHub-backed schempack.

## Instructions

### Requirements

- Git must be installed and available from the command line. Check with `git --version`.
- A GitHub account.
- A GitHub repository for the schempack.
- A GitHub access token that can read and write repository contents.

### Create the Repository

Create a new GitHub repository for your schempack. An empty repository is recommended for first setup.

When the mod asks for the repo link, paste one of these forms:

```text
https://github.com/username/repository.git
https://github.com/username/repository
git@github.com:username/repository.git
```

The mod stores the remote as an HTTPS GitHub repo.

### Create the Access Token

Fine-grained token:

1. Open GitHub `Settings -> Developer settings -> Personal access tokens -> Fine-grained tokens`.
2. Generate a new token for the schempack repository.
3. Set repository permission `Contents` to `Read and write`.
4. Copy the token after creating it. GitHub will not show it again.

Classic token:

1. Open GitHub `Settings -> Developer settings -> Personal access tokens -> Tokens (classic)`.
2. Generate a token with `repo` for private repositories, or `public_repo` for public repositories only.
3. Copy the token after creating it.

### Initialize In Game

1. Open Mindustry.
2. Open `Schematics`.
3. Press `SchemPack`.
4. Press `Edit Repository`.
5. Enter the repo link, GitHub username, and access token.
6. Save, then run compare or sync from the SchemPack screen.

`Force Push Local` overwrites the GitHub repo with the local schematic folder. Use it only when the local schempack is the version you want to keep.

## Releasing

The in-game mod browser needs a GitHub Release with the built jar attached. GitHub automatically adds the source code `.zip` and `.tar.gz` archives to every release.

Automatic release options:

1. Push a version tag:

```sh
git tag v1.0.0
git push origin v1.0.0
```

2. Or run the `Release` workflow manually from GitHub Actions and enter a tag like `v1.0.0`.

The workflow builds `build/libs/schempack-manager.jar`, creates the GitHub Release, and uploads the jar.

## Building for Desktop Testing

1. Install JDK **17**.
2. Run `gradlew jar` [1].
3. Your mod jar will be in the `build/libs` directory. **Only use this version for testing on desktop. It will not work with Android.**
To build an Android-compatible version, you need the Android SDK. You can either let Github Actions handle this, or set it up yourself. See steps below.

## Building Locally

Building locally takes more time to set up, but shouldn't be a problem if you've done Android development before.
1. Download the Android SDK, unzip it and set the `ANDROID_HOME` environment variable to its location.
2. Make sure you have API level 30 installed, as well as any recent version of build tools (e.g. 30.0.1)
3. Add a build-tools folder to your PATH. For example, if you have `30.0.1` installed, that would be `$ANDROID_HOME/build-tools/30.0.1`.
4. Run `gradlew deploy`. If you did everything correctlly, this will create a jar file in the `build/libs` directory that can be run on both Android and desktop. 
