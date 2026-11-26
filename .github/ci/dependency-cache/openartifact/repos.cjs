'use strict';

const sources = [
  ['maven-central', 'maven', 'https://repo.maven.apache.org/maven2'],
  ['maven-plugins', 'maven', 'https://plugins.gradle.org/m2'],
  ['npm', 'npm', 'https://registry.npmjs.org'],
  ['pypi', 'pypi', 'https://pypi.org/simple'],
  ['dockerhub', 'docker', 'https://registry-1.docker.io'],
  ['ghcr', 'docker', 'https://ghcr.io'],
  ['quay', 'docker', 'https://quay.io'],
  ['ecrpublic', 'docker', 'https://public.ecr.aws'],
  ['mcr', 'docker', 'https://mcr.microsoft.com'],
  ['k8s', 'docker', 'https://registry.k8s.io'],
  ['gcr', 'docker', 'https://mirror.gcr.io'],
  ['distroless', 'docker', 'https://gcr.io'],
  ['corretto', 'raw', 'https://corretto.aws/downloads'],
  ['gradle', 'raw', 'https://services.gradle.org/distributions'],
  ['kubectl', 'raw', 'https://dl.k8s.io'],
  ['kind', 'raw', 'https://github.com/kubernetes-sigs/kind/releases/download'],
  ['k3s', 'raw', 'https://github.com/k3s-io/k3s/releases/download'],
  ['crane', 'raw', 'https://github.com/google/go-containerregistry/releases/download'],
  ['argo', 'raw', 'https://github.com/argoproj/argo-workflows/releases/download'],
  ['helm', 'raw', 'https://get.helm.sh'],
  ['jdk', 'raw', 'https://download.java.net'],
  ['temurin', 'raw', 'https://github.com/adoptium/temurin21-binaries/releases/download'],
  ['elastic', 'raw', 'https://artifacts.elastic.co'],
];

const repositories = sources.map(([name, format, upstream_url]) => ({
  name,
  format,
  upstream_url,
  public: true,
  repo_type: 'proxy',
  presign_downloads: false,
  cache_ttl_seconds: 86400,
  negative_cache_ttl_seconds: 300,
}));
repositories.push({
  name: 'maven-all',
  format: 'maven',
  repo_type: 'group',
  public: true,
  member_repos: ['maven-central', 'maven-plugins'],
});
module.exports = { repositories };
