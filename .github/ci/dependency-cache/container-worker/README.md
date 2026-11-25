# Native Docker guest validation

This opt-in launcher boots a new x86-64 Ubuntu KVM guest with its own Docker daemon. Its network can reach the job's dependency-cache Unix socket through one explicit QEMU TCP forward. The trusted host uses a separate loopback-only SSH forward to transport source from `git archive HEAD` and return the command's exit code. The guest has a disposable writable disk and its own administrator privileges. It receives no host directory mounts, Docker socket, Git or AWS credentials, SSH agent, or host environment variables.

Use an ephemeral Linux host with QEMU, `qemu-img`, `cloud-localds`, `ssh`, `socat`, and an unprivileged runner account with access to `/dev/kvm`. Run preparation from protected main under a read-only checkout. It fetches a SHA-256-pinned Ubuntu base and installs Ubuntu's signed Docker/OpenJDK packages and Testcontainers Python 4.15.0. Preparation requires internet access and never receives candidate source. Save the printed guest-image SHA-256 in trusted configuration alongside the private read-only image. Preparing at different times can produce different installed package versions and image hashes.

```sh
python3 .github/ci/dependency-cache/container-worker/worker.py prepare --output "$RUNNER_TEMP/migrations-worker.qcow2"
```

The dedicated validation workflow creates a deterministic OCI image in private temporary storage from Ubuntu’s signed `busybox-static` package. The probe broker verifies the image manifest, config and layer digests before serving them offline. It exports the manifest hex in `CI_CONTAINER_PROBE_DIGEST`. Start the broker and pass its Unix socket. Checkout the workflow and launcher from the protected base commit. Put the PR commit in a separate checkout and supply it only as `--source`.

```sh
python3 .github/ci/dependency-cache/container-worker/worker.py run \
  --image "$RUNNER_TEMP/migrations-worker.qcow2" --sha256 "$CI_WORKER_IMAGE_SHA256" \
  --source "$GITHUB_WORKSPACE/candidate" -- @smoke
```

`@smoke` runs the base checkout's trusted Testcontainers probe. It pulls a digest-addressed BusyBox image through the local dependency broker, executes a process inside nested Docker, checks cache connectivity from the container, and checks direct public and instance-metadata connections fail. Other commands run against `/home/runner/source` in the guest. Images for Docker, Kind, and BuildKit must be routed to `10.0.2.100:7777/<registry>/<repository>` by their native clients. Any missing tools, Java wrapper routes, image digests or unhandled registry paths can fail closed. Workflow adoption requires a successful native smoke on the targeted runner image.
