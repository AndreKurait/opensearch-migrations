'use strict';

const {spawn} = require('node:child_process');
const {createHash} = require('node:crypto');
const {access, mkdir, mkdtemp: temporaryDirectory, readdir, readFile, rm, writeFile} = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {fetchJdk} = require('./corretto.cjs');

const repository = path.resolve(__dirname, '../../..');
const imageDigest = 'sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa';
const rawDigest = 'b3ccb81e850fc635e8391d5e46e3b322f4b9b805cc99a8e91766c0718012af3c';
const npmIntegrity = '41Cifkg6e8TylSpdtTpeLVMqvSBEVzTttHvERD741+pnZ8ANv0004MRL43QKPDlK9cGvNp6NZWZUBlbGXYxxng==';
const wheelDigest = '946d195a0d259cbba61165e88e65941f16e9b36ea6ddb97f00452bae8b1287d3';

async function run(command, args, {cwd, env = process.env}) {
  await new Promise((accept, reject) => {
    const child = spawn(command, args, {cwd, env, stdio: 'inherit'});
    child.once('error', reject);
    child.once('close', (code, signal) => {
      if (code === 0) accept();
      else reject(new Error(`${path.basename(command)} exited with ${signal || code}`));
    });
  });
}

async function installedGradle() {
  if (process.env.GRADLE_BIN) return process.env.GRADLE_BIN;
  const wrapper = await readFile(path.join(repository, 'gradle/wrapper/gradle-wrapper.properties'), 'utf8');
  const release = /\/(gradle-[\d.]+)-(bin|all)\.zip(?:\s|$)/.exec(wrapper);
  if (!release) throw new Error('Cannot identify the repository Gradle distribution');
  const home = process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle');
  const dists = path.join(home, 'wrapper/dists', `${release[1]}-${release[2]}`);
  for (let attempt = 0; attempt < 2; attempt++) {
    const dirs = await readdir(dists).catch(error => { if (error.code === 'ENOENT') return []; throw error; });
    for (const dir of dirs) {
      const binary = path.join(dists, dir, release[1], 'bin', 'gradle');
      try { await access(binary); return binary; } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    if (attempt === 0) {
      const routedJar = process.env.CI_DEPENDENCY_GRADLE_WRAPPER_JAR;
      if (!routedJar) throw new Error('The pinned Gradle installation or a proxy-configured wrapper is required');
      const properties = await readFile(routedJar.replace(/\.jar$/, '.properties'), 'utf8');
      const expected = new URL(`/raw/gradle/${release[1]}-${release[2]}.zip`, process.env.CI_DEPENDENCY_PROXY_URL);
      if (!properties.split(/\r?\n/).includes(`distributionUrl=${expected.href}`)) throw new Error('Gradle wrapper bypasses the dependency proxy');
      await run(path.join(repository, 'gradlew'), ['--version'], {cwd: repository});
    }
  }
  throw new Error('Missing the pinned Gradle installation after running its wrapper');
}

async function smoke() {
  const proxy = new URL(process.env.CI_DEPENDENCY_PROXY_URL);
  const repositoryUrl = new URL(process.env.MAVEN_REPOSITORY_URL);
  if (repositoryUrl.origin !== proxy.origin || repositoryUrl.pathname.replace(/\/$/, '') !== '/maven' ||
      !['http:', 'https:'].includes(proxy.protocol) || proxy.pathname !== '/' || proxy.username || proxy.password) {
    throw new Error('MAVEN_REPOSITORY_URL must identify the dependency proxy Maven route');
  }
  const dir = await temporaryDirectory(path.join(os.tmpdir(), 'dependency-cache-smoke-'));
  try {
    const archive = await fetchJdk(proxy, dir);
    const jdk = path.join(dir, 'jdk');
    await mkdir(jdk);
    await run('tar', ['-xzf', archive, '--strip-components=1', '-C', jdk], {cwd: dir});
    const javaHome = process.platform === 'darwin' ? path.join(jdk, 'Contents', 'Home') : jdk;
    const javaEnv = {...process.env, JAVA_HOME: javaHome, PATH: path.join(javaHome, 'bin') + path.delimiter + process.env.PATH};
    await run(path.join(javaHome, 'bin', 'java'), ['-version'], {cwd: dir, env: javaEnv});
    const cacheScript = JSON.stringify(path.join(repository, 'gradle/remote-cache.gradle'));
    await writeFile(path.join(dir, 'settings.gradle'), `rootProject.name = 'cache-protocol-smoke'\napply from: ${cacheScript}\n`);
    await writeFile(path.join(dir, 'build.gradle'), `
plugins { id 'java' }
repositories {
    maven {
        url = uri(System.getenv('MAVEN_REPOSITORY_URL'))
        allowInsecureProtocol = url.scheme == 'http'
    }
}
configurations { smoke }
dependencies { smoke 'junit:junit:4.13.2' }
tasks.register('checkArtifacts') {
    doLast {
        def names = configurations.smoke.files.collect { it.name }.sort()
        if (names != ['hamcrest-core-1.3.jar', 'junit-4.13.2.jar']) throw new GradleException("Unexpected resolution: " + names)
        println("Resolved " + names.join(', '))
    }
}
`);
    const sourceDir = path.join(dir, 'src/main/java');
    await mkdir(sourceDir, {recursive:true});
    await writeFile(path.join(sourceDir, 'PublicCacheCheck.java'), 'public class PublicCacheCheck { public static int value() { return 42; } }\n');
    const requireCacheHit = process.env.CI_GRADLE_BUILD_CACHE_PUSH === 'false';
    // A cold compile must fail this check in read mode. Both jobs use fresh Gradle homes and clean outputs.
    if (requireCacheHit) await writeFile(path.join(dir,'require-hit.init.gradle'),
      `gradle.taskGraph.afterTask { task, state -> if (task.path == ':compileJava' && state.skipMessage != 'FROM-CACHE') throw new GradleException("Expected remote FROM-CACHE for compileJava") }\n`);
    const cacheCheck = requireCacheHit ? ['--init-script', path.join(dir,'require-hit.init.gradle')] : [];
    await run(await installedGradle(), ['--no-daemon','--build-cache', ...cacheCheck, '--gradle-user-home', path.join(dir, '.gradle-user'),
      '--project-dir', dir, 'checkArtifacts','compileJava'], {cwd: dir, env: javaEnv});

    // Fresh client caches and independently pinned file checksums exercise metadata and package downloads.
    const env = {...process.env, npm_config_registry: new URL('/npm/', proxy).href, npm_config_audit: 'false',
      npm_config_update_notifier: 'false', npm_config_cache: path.join(dir, '.npm'), npm_config_fetch_retries: '0',
      PIP_INDEX_URL: new URL('/pypi/simple/', proxy).href, PIP_EXTRA_INDEX_URL: '', PIP_CONFIG_FILE: '/dev/null',
      PIP_DISABLE_PIP_VERSION_CHECK: '1'};
    await run('npm', ['pack', 'is-number@7.0.0', '--ignore-scripts'], {cwd: dir, env});
    const tarball = await readFile(path.join(dir, 'is-number-7.0.0.tgz'));
    if (createHash('sha512').update(tarball).digest('base64') !== npmIntegrity) throw new Error('npm package integrity mismatch');
    await run('python3', ['-m', 'pip', 'download', '--no-deps', '--no-cache-dir', '--only-binary=:all:', '--retries', '0',
      'idna==3.10'], {cwd: dir, env});
    const wheel = await readFile(path.join(dir, 'idna-3.10-py3-none-any.whl'));
    if (createHash('sha256').update(wheel).digest('hex') !== wheelDigest) throw new Error('Python wheel integrity mismatch');

    const source = `${proxy.host}/dockerhub/library/hello-world@${imageDigest}`;
    const skopeo = process.env.SKOPEO_BIN || 'skopeo';
    try {
      await run(skopeo, ['--insecure-policy', 'copy', `--src-tls-verify=${proxy.protocol === 'https:'}`,
        `docker://${source}`, `dir:${path.join(dir, 'image')}`], {cwd: dir});
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await run('docker', ['pull', '--platform', 'linux/amd64', source], {cwd: dir});
    }

    const raw = await fetch(new URL('/raw/kubectl/release/v1.35.0/bin/linux/amd64/kubectl.sha256', proxy));
    if (!raw.ok) throw new Error(`kubectl checksum returned ${raw.status}`);
    const bytes = Buffer.from(await raw.arrayBuffer());
    if (createHash('sha256').update(bytes).digest('hex') !== rawDigest || !/^[a-f0-9]{64}$/.test(bytes.toString())) {
      throw new Error('kubectl checksum fixture differs from its approved digest');
    }
    const fs = require('node:fs');
    if (Object.keys(process.env).some(key => /^(ACTIONS_|AWS_|GITHUB_TOKEN$)/.test(key)) || fs.existsSync('/var/run/docker.sock')) {
      throw new Error('Candidate can access host credentials or Docker');
    }
    console.log('PR-modified assertion passed inside the offline worker.');
    console.log('Verified the pinned Corretto JDK, Gradle transitive dependencies, npm and Python packages, OCI image layers, and the kubectl checksum through the proxy.');
  } finally {
    await rm(dir, {recursive: true, force: true});
  }
}

if (require.main === module) smoke().catch(error => { console.error(error.message); process.exitCode = 1; });
