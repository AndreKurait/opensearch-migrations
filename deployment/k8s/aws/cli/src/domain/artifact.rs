//! Deploy-artifact resolution: where the CFN template, the helm chart, and the
//! buildkit/gradle build come from, for the headless deploy.
//!
//! Two lanes, mirroring `aws-bootstrap.sh`:
//!   * **`--build` (source):** CFN template is synthesized by Gradle
//!     (`:deployment:migration-assistant-solution:cdkSynthMinified`) into
//!     `cdk.out-minified/`, the chart is the in-repo chart dir, and images are
//!     built + pushed by `:buildImages:buildImagesToRegistry`. The heavy lifting
//!     (CDK synth, buildkit) stays in Gradle — this module only computes the
//!     commands + paths, it does NOT reimplement them.
//!   * **`--version` (release):** the template + chart `.tgz` are downloaded
//!     from the GitHub release for the resolved version.
//!
//! This module is pure (paths, URLs, command vectors); the side-effecting
//! shell-outs live on [`crate::app::App`], so they go through the runner seam
//! and stay testable.

use crate::cfn::TemplateVariant;

/// Base URL for a release's downloadable artifacts (templates + chart tgz).
pub fn release_base_url(repo: &str, version: &str) -> String {
    format!("https://github.com/{repo}/releases/download/{version}")
}

/// The local path the Gradle CDK synth writes the chosen template to.
/// Mirrors `cdk.out-minified/<TemplateName>` under the solution package.
pub fn synthesized_template_path(base_dir: &str, variant: TemplateVariant) -> String {
    format!(
        "{base_dir}/deployment/migration-assistant-solution/cdk.out-minified/{}",
        variant.template_name()
    )
}

/// The in-repo helm chart directory used on the `--build` lane.
pub fn local_chart_dir(base_dir: &str) -> String {
    format!("{base_dir}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo")
}

/// The release chart tarball filename for a version.
pub fn chart_tarball_name(version: &str) -> String {
    format!("migration-assistant-{version}.tgz")
}

/// The helm values files applied (in order) for the `--build` lane: the
/// chart's base `values.yaml` then the EKS overrides `valuesEks.yaml`. Mirrors
/// the reference's `-f $chart/values.yaml -f $chart/valuesEks.yaml`. Without
/// `valuesEks.yaml` the EKS-specific config (nodepool/storage/networking) is
/// missing and pods never become ready.
pub fn local_chart_values(chart_dir: &str) -> Vec<String> {
    vec![
        format!("{chart_dir}/values.yaml"),
        format!("{chart_dir}/valuesEks.yaml"),
    ]
}

/// Gradle args (after the `gradlew` program) that synthesize the minified CFN
/// templates. `STACK_NAME_SUFFIX=""` is set by the caller as an env var so the
/// output filenames are the predictable [`TemplateVariant::template_name`].
pub fn cdk_synth_args(base_dir: &str) -> Vec<String> {
    vec![
        "-p".into(),
        base_dir.into(),
        ":deployment:migration-assistant-solution:cdkSynthMinified".into(),
        "-x".into(),
        "test".into(),
    ]
}

/// A buildkit builder name derived from the kube context, sanitized to
/// `[A-Za-z0-9_-]`. Mirrors `builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}`.
pub fn builder_name(kube_context: &str) -> String {
    let sanitized: String = kube_context
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                c
            } else {
                '-'
            }
        })
        .collect();
    format!("builder-{sanitized}")
}

/// The full image-build shell block (run via `bash -c`), mirroring the
/// `--build` path of `aws-bootstrap.sh`: ensure a kubernetes-driver buildkit
/// builder exists (the EKS agents have no local docker build daemon), ECR-login
/// via docker, then `gradlew :buildImages:buildImagesToRegistry` with one retry.
/// Kept as a pure string so it's unit-testable; the side effect is the caller's
/// `runner.run("bash", ["-c", script])`.
#[allow(clippy::too_many_arguments)]
pub fn build_images_script(
    base_dir: &str,
    region: &str,
    ecr_host: &str,
    registry: &str,
    builder: &str,
    image_tag: &str,
    kube_context: &str,
    skip_test_images: bool,
) -> String {
    let skip_arg = if skip_test_images {
        "-PskipTestImages=true"
    } else {
        ""
    };
    // `set -euo pipefail` so any sub-step failure propagates a non-zero rc.
    // The builder is reused if healthy, else (re)created via the repo's
    // eksKubernetesBuildkit backend — exactly as the reference does.
    format!(
        r#"set -euo pipefail
export MULTI_ARCH_NATIVE=true
export KUBE_CONTEXT="{kube_context}"
export MIGRATIONS_REPO_ROOT_DIR="{base_dir}"
BUILDER_NAME="{builder}"
if docker buildx inspect "$BUILDER_NAME" --bootstrap >/dev/null 2>&1; then
  echo "Buildkit already configured and healthy, skipping setup"
else
  echo "Setting up buildkit (kubernetes driver) ..."
  docker buildx rm "$BUILDER_NAME" >/dev/null 2>&1 || true
  source "{base_dir}/buildImages/backends/eksKubernetesBuildkit.sh"
  setup_build_backend
fi
echo "Logging in to ECR registry: {ecr_host}"
aws ecr get-login-password --region "{region}" | docker login --username AWS --password-stdin "{ecr_host}"
echo "Building images to {registry}"
"{base_dir}/gradlew" -p "{base_dir}" :buildImages:buildImagesToRegistry \
  -PregistryEndpoint="{registry}" -Pbuilder="$BUILDER_NAME" -PimageVersion="{image_tag}" {skip_arg} -x test \
  || {{ echo "Image build failed, retrying in 10s..."; sleep 10; \
       "{base_dir}/gradlew" -p "{base_dir}" :buildImages:buildImagesToRegistry \
         -PregistryEndpoint="{registry}" -Pbuilder="$BUILDER_NAME" -PimageVersion="{image_tag}" {skip_arg} -x test; }}
echo "Cleaning up buildx builder ..."
docker buildx rm "$BUILDER_NAME" >/dev/null 2>&1 || true
"#
    )
}

/// Shell block (run via `bash -c`) that mirrors the third-party images + helm
/// charts into the private ECR and writes a private-ECR helm values override to
/// `values_out`. Mirrors the `push_images_to_ecr` block of `aws-bootstrap.sh`:
/// it sources the chart's own `privateEcrManifest.sh` (the IMAGES/CHARTS lists),
/// `mirrorToEcr.sh` (`mirror_images_to_ecr`/`mirror_charts_to_ecr`), and
/// `generatePrivateEcrValues.sh` (`generate_private_ecr_values`) — the proven
/// scripts vendored in the chart — rather than reimplementing them in Rust.
///
/// Without this, the chart's `ma-dependency-installer` pre-install hook tries to
/// pull cert-manager/argo/strimzi/etc. charts+images from public registries
/// (which the in-cluster job can't reach / isn't configured for) and its pods
/// hang until `activeDeadlineSeconds` → `DeadlineExceeded`. The generated values
/// repoint every sub-chart at `oci://<ecr>/charts/...` + `<ecr>/mirrored/...`.
pub fn mirror_to_ecr_script(
    scripts_dir: &str,
    ecr_host: &str,
    region: &str,
    values_out: &str,
) -> String {
    format!(
        r#"set -euo pipefail
. "{scripts_dir}/privateEcrManifest.sh"
. "{scripts_dir}/mirrorToEcr.sh"
. "{scripts_dir}/generatePrivateEcrValues.sh"
echo "Mirroring third-party images to private ECR ({ecr_host}) ..."
mirror_images_to_ecr "{ecr_host}" "{region}" "$IMAGES"
echo "Mirroring helm charts to private ECR ..."
mirror_charts_to_ecr "{ecr_host}" "{region}" "$CHARTS"
echo "Generating private-ECR helm values override → {values_out}"
generate_private_ecr_values "{ecr_host}" > "{values_out}"
echo "wrote {values_out}"
"#
    )
}

/// Shell block (run via `bash -c`) that runs `aws cloudformation deploy` while a
/// background poller tails `describe-stack-events` so the operator sees live
/// resource-by-resource progress (and, on failure, the failing resource's
/// reason). `aws cloudformation deploy` ALONE prints "Waiting for stack
/// create/update to complete" and then goes SILENT for the whole ~15-20 min
/// VPC+EKS create — leaving the console looking hung. Mirrors the
/// `run_cfn_deploy`/`stream_cfn_events` pair in `aws-bootstrap.sh`. Each element
/// of `params` is one parameter-override token (`Key=Value`), shell-quoted into
/// the deploy command.
pub fn cfn_deploy_script(
    region: &str,
    stack_name: &str,
    template_file: &str,
    params: &[String],
) -> String {
    // Shell-quote each parameter override (values may contain `=`,`,`,`/` etc.).
    let param_args = params
        .iter()
        .map(|p| format!("'{}'", p.replace('\'', "'\\''")))
        .collect::<Vec<_>>()
        .join(" ");
    format!(
        r#"set -uo pipefail
STACK="{stack_name}"
REGION="{region}"
SEEN="$(mktemp)"
# Background event tailer: print each new stack event (status, type, id, reason)
# so a long silent CFN create shows live progress.
stream_events() {{
  while true; do
    aws cloudformation describe-stack-events --stack-name "$STACK" --region "$REGION" \
      --query 'StackEvents[].[EventId,Timestamp,ResourceStatus,ResourceType,LogicalResourceId,ResourceStatusReason]' \
      --output text 2>/dev/null | tac | while IFS=$'\t' read -r eid ts status rtype logical reason; do
        grep -qxF "$eid" "$SEEN" 2>/dev/null && continue
        echo "$eid" >> "$SEEN"
        [ "$reason" = "None" ] && reason=""
        printf '  %-22s %-28s %-38s %s%s\n' "$ts" "$status" "$rtype" "$logical" "${{reason:+  ($reason)}}"
      done
    sleep 10
  done
}}
stream_events &
STREAM_PID=$!
aws cloudformation deploy \
  --region "$REGION" \
  --stack-name "$STACK" \
  --template-file "{template_file}" \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
  --no-fail-on-empty-changeset \
  --parameter-overrides {param_args}
rc=$?
kill $STREAM_PID 2>/dev/null; wait $STREAM_PID 2>/dev/null
rm -f "$SEEN"
# On failure, surface the failed resources explicitly (deploy's own error is terse).
if [ $rc -ne 0 ]; then
  echo "CFN deploy failed (rc=$rc) — failed resources:" >&2
  aws cloudformation describe-stack-events --stack-name "$STACK" --region "$REGION" \
    --query 'StackEvents[?contains(ResourceStatus,`FAILED`)].[LogicalResourceId,ResourceStatus,ResourceStatusReason]' \
    --output text 2>/dev/null | head -20 >&2
fi
exit $rc
"#
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn template_path_uses_variant_name() {
        let p = synthesized_template_path("/repo", TemplateVariant::CreateVpc);
        assert!(
            p.ends_with("cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json")
        );
        let p = synthesized_template_path("/repo", TemplateVariant::ImportVpc);
        assert!(p.ends_with("Migration-Assistant-Infra-Import-VPC-eks.template.json"));
    }

    #[test]
    fn builder_name_sanitizes() {
        assert_eq!(
            builder_name("migration-eks-eksbyos-p200"),
            "builder-migration-eks-eksbyos-p200"
        );
        assert_eq!(
            builder_name("ctx:weird/chars.x"),
            "builder-ctx-weird-chars-x"
        );
    }

    #[test]
    fn build_images_script_has_setup_login_and_gradle() {
        let s = build_images_script(
            "/repo",
            "us-east-1",
            "123.dkr.ecr.us-east-1.amazonaws.com",
            "123.dkr.ecr.us-east-1.amazonaws.com/ecr",
            "builder-x",
            "tag1",
            "migration-eks-x",
            true,
        );
        // buildkit setup (reuse-or-create), ECR login, gradle build, skip-test.
        assert!(s.contains("docker buildx inspect \"$BUILDER_NAME\" --bootstrap"));
        assert!(s.contains("eksKubernetesBuildkit.sh"));
        assert!(s.contains("setup_build_backend"));
        assert!(s.contains("docker login --username AWS --password-stdin"));
        assert!(s.contains(":buildImages:buildImagesToRegistry"));
        assert!(s.contains("-PregistryEndpoint=\"123.dkr.ecr.us-east-1.amazonaws.com/ecr\""));
        assert!(s.contains("-Pbuilder=\"$BUILDER_NAME\""));
        assert!(s.contains("-PimageVersion=\"tag1\""));
        assert!(s.contains("-PskipTestImages=true"));
        assert!(s.contains("set -euo pipefail"));
        // skip-test omitted when false.
        let s2 = build_images_script("/r", "r", "h", "reg", "b", "t", "c", false);
        assert!(!s2.contains("-PskipTestImages=true"));
    }

    #[test]
    fn release_urls() {
        assert_eq!(
            release_base_url("opensearch-project/opensearch-migrations", "2.8.2"),
            "https://github.com/opensearch-project/opensearch-migrations/releases/download/2.8.2"
        );
        assert_eq!(chart_tarball_name("2.8.2"), "migration-assistant-2.8.2.tgz");
    }

    #[test]
    fn cdk_synth_args_shape() {
        let a = cdk_synth_args("/repo");
        assert_eq!(a[0], "-p");
        assert_eq!(a[1], "/repo");
        assert!(a.iter().any(|x| x.contains("cdkSynthMinified")));
    }
}
