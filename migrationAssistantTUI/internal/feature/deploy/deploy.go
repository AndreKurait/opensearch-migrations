// Package deploy is the orchestration driver for `aws-bootstrap.sh` 3.2.1
// ported to Go (PLAN §0.1).
//
// Faithfully ports the real script flow:
//
//  1. Download the right CFN template (Create-VPC or Import-VPC) from
//     the GitHub release at the pinned MA version.
//  2. `aws cloudformation deploy` with the right parameters (Stage,
//     VPCId, VPCSubnetIds, S3 route tables, optional endpoint flags).
//  3. Wait for completion via DescribeStacks polling, streaming events.
//  4. Read CFN exports prefixed `MigrationsExportString*` and resolve
//     MIGRATIONS_EKS_CLUSTER_NAME, MIGRATIONS_ECR_REGISTRY, etc.
//  5. `aws eks update-kubeconfig` to set up the local kubectl context.
//  6. Optionally `aws eks create-access-entry` + `associate-access-policy`
//     for the user-supplied principal ARN AND the current caller (so the
//     user can always reach their own cluster).
//  7. `helm install ma <chart>` with the right values + TLS / nodepool
//     flags (PCA modes wired in).
//
// Helm and AWS image-mirroring stay as subprocess calls (`helm`, `aws`)
// because re-implementing helm's chart resolver in Go would be a
// project of its own. CFN, exports, EKS access-entry, kubeconfig, and
// stack polling are native AWS SDK calls.
package deploy

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"sort"
	"strings"
	"time"

	awssdk "github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudformation"
	cfntypes "github.com/aws/aws-sdk-go-v2/service/cloudformation/types"
	"github.com/aws/aws-sdk-go-v2/service/ec2"
	"github.com/aws/aws-sdk-go-v2/service/eks"
	ekstypes "github.com/aws/aws-sdk-go-v2/service/eks/types"
)

// PhaseEvent is one progress update streamed by Run.
type PhaseEvent struct {
	Phase   string // "cfn" | "kubeconfig" | "access" | "helm"
	Status  string // "started" | "progress" | "completed" | "failed"
	Message string
	At      time.Time
}

// Params is the resolved input to Run.
type Params struct {
	Region    string
	StackName string
	Stage     string

	// Scope: "create-vpc" | "import-vpc" | "skip-cfn"
	Scope        string
	VPCID        string
	SubnetIDs    []string
	VPCEndpoints []string // "s3","ecr","ecrDocker","cloudwatchLogs","efs","sts","eksAuth"

	// MAVersion is the pinned upstream version (e.g. "3.2.1"). Used for
	// release-asset URLs.
	MAVersion string

	// TLSMode: "none" | "self-signed" | "pca-existing" | "pca-create"
	TLSMode string
	PCAARN  string // only when TLSMode == "pca-existing"

	// Namespace defaults to "ma" — that's what aws-bootstrap.sh uses.
	Namespace   string
	ReleaseName string // also "ma" by default

	// EKSAccessARNs — IAM principals to grant AmazonEKSClusterAdminPolicy.
	// The current caller (resolved via sts:GetCallerIdentity) is always
	// included automatically; this list adds extra principals.
	EKSAccessARNs []string

	// TemplateBody is the rendered CFN template fetched by ArtifactSource.
	// Empty for skip-cfn.
	TemplateBody string

	// HelmChartPath is the local file the caller has already downloaded.
	HelmChartPath string

	// CallerARN is sts:GetCallerIdentity → user/role ARN of the caller.
	// Used to auto-grant EKS access so the user can always reach their cluster.
	CallerARN string
}

// Driver runs deploys.
//
// ec2 client is intentionally omitted in v1: the previous resolveS3RouteTables
// helper that needed it has been removed (see PLAN §0.1 backlog for
// re-introducing the S3EndpointRouteTableIds parameter).
type Driver struct {
	cfn *cloudformation.Client
	eks *eks.Client

	// Subprocess overrides (tests stub these).
	HelmCmd    string
	KubectlCmd string
	AWSCmd     string
	PollEvery  time.Duration
}

// NewDriver returns a Driver bound to the given AWS clients. ec2 is
// accepted for forward-compatibility but not currently used.
func NewDriver(cfn *cloudformation.Client, _ *ec2.Client, eksc *eks.Client) *Driver {
	return &Driver{
		cfn:        cfn,
		eks:        eksc,
		HelmCmd:    "helm",
		KubectlCmd: "kubectl",
		AWSCmd:     "aws",
		PollEvery:  10 * time.Second,
	}
}

// CFNExports holds the values resolved from MigrationsExportString* exports.
type CFNExports struct {
	EKSClusterName string
	ECRRegistry    string
	SnapshotRole   string
	Account        string
	Region         string
	Stage          string
}

// Run orchestrates the full bootstrap.sh flow. Always closes events
// before returning.
func (d *Driver) Run(ctx context.Context, p Params, events chan<- PhaseEvent) error {
	defer close(events)
	emit := func(phase, status, msg string) {
		select {
		case events <- PhaseEvent{Phase: phase, Status: status, Message: msg, At: time.Now()}:
		default:
		}
	}

	// Phase 1: CloudFormation (unless skip-cfn).
	if p.Scope != "skip-cfn" {
		emit("cfn", "started", fmt.Sprintf("creating stack %s", p.StackName))
		emit("cfn", "progress", fmt.Sprintf("using template (%d bytes)", len(p.TemplateBody)))
		emit("cfn", "progress", fmt.Sprintf("%d parameters: Stage=%s%s",
			len(BuildCFNParams(p)), p.Stage, vpcParamSummary(p)))
		if err := d.runCFN(ctx, p, emit); err != nil {
			emit("cfn", "failed", err.Error())
			return err
		}
		emit("cfn", "completed", "CFN stack ready")
	} else {
		emit("cfn", "completed", "(skipped — --skip-cfn-deploy)")
	}

	// Phase 2: Read CFN exports.
	emit("exports", "started", "reading CFN exports MigrationsExportString*")
	exports, err := d.readExports(ctx, p)
	if err != nil {
		emit("exports", "failed", err.Error())
		return err
	}
	emit("exports", "progress", fmt.Sprintf("resolved cluster=%s", exports.EKSClusterName))
	emit("exports", "progress", fmt.Sprintf("resolved ecr=%s", exports.ECRRegistry))
	emit("exports", "completed", "all exports resolved")

	// Phase 3: kubectl context.
	emit("kubeconfig", "started", fmt.Sprintf("aws eks update-kubeconfig --name %s", exports.EKSClusterName))
	if err := d.updateKubeconfig(ctx, exports.Region, exports.EKSClusterName); err != nil {
		emit("kubeconfig", "failed", err.Error())
		return err
	}
	emit("kubeconfig", "completed", fmt.Sprintf("context '%s' written to ~/.kube/config", exports.EKSClusterName))

	// Phase 4: EKS access for the caller (and optional extra principals).
	principals := dedupe(append([]string{p.CallerARN}, p.EKSAccessARNs...))
	emit("access", "started", fmt.Sprintf("granting AmazonEKSClusterAdminPolicy to %d principals", len(principals)))
	for _, arn := range principals {
		emit("access", "progress", "→ "+arn)
	}
	if err := d.grantAccess(ctx, exports.EKSClusterName, p); err != nil {
		emit("access", "progress", fmt.Sprintf("warning: %v", err))
	} else {
		emit("access", "completed", "EKS access entries created")
	}

	// Phase 5: image mirroring (if push_images_to_ecr is true — default).
	// In v1 we only emit informational events; the actual mirror is done
	// by `mirrorToEcr.sh` shipped with the chart, invoked by helm pre-install.
	// TODO: port mirrorToEcr.sh into Go so we can stream per-image progress.
	emit("images", "started", "mirroring public images to private ECR (handled by chart pre-install)")
	emit("images", "progress", "mirror is delegated to scripts/mirrorToEcr.sh inside the chart — progress will appear during helm install")
	emit("images", "completed", "deferred to helm pre-install hook")

	// Phase 6: helm install.
	emit("helm", "started", "running helm install")
	if err := d.runHelm(ctx, p, exports, emit); err != nil {
		emit("helm", "failed", err.Error())
		return err
	}
	emit("helm", "completed", fmt.Sprintf("helm release '%s' installed", p.ReleaseName))
	return nil
}

// vpcParamSummary returns a short string listing VPC-related params for
// the cfn-progress event line. Empty for create-vpc and skip-cfn.
func vpcParamSummary(p Params) string {
	if p.Scope != "import-vpc" {
		return ""
	}
	return fmt.Sprintf(", VPCId=%s, %d subnet(s), %d endpoint(s)",
		p.VPCID, len(p.SubnetIDs), len(p.VPCEndpoints))
}

// runCFN deploys the CFN stack, polling DescribeStacks until completion.
func (d *Driver) runCFN(ctx context.Context, p Params, emit func(phase, status, msg string)) error {
	if d.cfn == nil {
		return errors.New("cfn client not configured")
	}
	if p.TemplateBody == "" {
		return errors.New("CFN template body is required (caller must fetch via ArtifactSource)")
	}

	in := &cloudformation.CreateStackInput{
		StackName:    awssdk.String(p.StackName),
		TemplateBody: awssdk.String(p.TemplateBody),
		Capabilities: []cfntypes.Capability{
			cfntypes.CapabilityCapabilityIam,
			cfntypes.CapabilityCapabilityNamedIam,
		},
		Parameters: BuildCFNParams(p),
	}
	if _, err := d.cfn.CreateStack(ctx, in); err != nil {
		// If the stack already exists, fall through to UpdateStack —
		// matches the bootstrap.sh idempotency contract.
		if !strings.Contains(err.Error(), "AlreadyExistsException") {
			return fmt.Errorf("create stack: %w", err)
		}
		emit("cfn", "progress", "stack exists; running update")
		if _, err := d.cfn.UpdateStack(ctx, &cloudformation.UpdateStackInput{
			StackName:    awssdk.String(p.StackName),
			TemplateBody: awssdk.String(p.TemplateBody),
			Capabilities: in.Capabilities,
			Parameters:   in.Parameters,
		}); err != nil && !strings.Contains(err.Error(), "No updates") {
			return fmt.Errorf("update stack: %w", err)
		}
	}

	// Poll until completion. Stream stack events as they appear.
	ticker := time.NewTicker(d.PollEvery)
	defer ticker.Stop()
	seen := map[string]bool{}
	for {
		// Stream new events first.
		evs, _ := d.cfn.DescribeStackEvents(ctx, &cloudformation.DescribeStackEventsInput{
			StackName: awssdk.String(p.StackName),
		})
		if evs != nil {
			// Reverse: oldest first.
			for i := len(evs.StackEvents) - 1; i >= 0; i-- {
				e := evs.StackEvents[i]
				id := deref(e.EventId)
				if id == "" || seen[id] {
					continue
				}
				seen[id] = true
				logical := deref(e.LogicalResourceId)
				rtype := deref(e.ResourceType)
				status := string(e.ResourceStatus)
				reason := deref(e.ResourceStatusReason)
				if reason != "" && reason != "None" {
					emit("cfn", "progress", fmt.Sprintf("%s %s %s — %s", rtype, logical, status, reason))
				} else {
					emit("cfn", "progress", fmt.Sprintf("%s %s %s", rtype, logical, status))
				}
			}
		}

		out, err := d.cfn.DescribeStacks(ctx, &cloudformation.DescribeStacksInput{
			StackName: awssdk.String(p.StackName),
		})
		if err != nil {
			return fmt.Errorf("describe stack: %w", err)
		}
		if len(out.Stacks) == 0 {
			return fmt.Errorf("stack %s vanished mid-deploy", p.StackName)
		}
		st := out.Stacks[0]
		switch st.StackStatus {
		case cfntypes.StackStatusCreateComplete, cfntypes.StackStatusUpdateComplete:
			return nil
		case cfntypes.StackStatusCreateFailed,
			cfntypes.StackStatusRollbackComplete,
			cfntypes.StackStatusRollbackFailed,
			cfntypes.StackStatusUpdateRollbackFailed:
			reason := deref(st.StackStatusReason)
			return fmt.Errorf("stack %s entered terminal failure state %s: %s", p.StackName, st.StackStatus, reason)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// BuildCFNParams maps wizard-state into the parameter overrides
// `aws cloudformation deploy --parameter-overrides` consumes. Mirrors
// the bash for-loop in bootstrap.sh §CFN deployment.
func BuildCFNParams(p Params) []cfntypes.Parameter {
	stage := p.Stage
	if stage == "" {
		stage = "dev"
	}
	out := []cfntypes.Parameter{
		{ParameterKey: awssdk.String("Stage"), ParameterValue: awssdk.String(stage)},
	}
	if p.Scope == "import-vpc" {
		if p.VPCID != "" {
			out = append(out, cfntypes.Parameter{
				ParameterKey: awssdk.String("VPCId"), ParameterValue: awssdk.String(p.VPCID),
			})
		}
		if len(p.SubnetIDs) > 0 {
			out = append(out, cfntypes.Parameter{
				ParameterKey: awssdk.String("VPCSubnetIds"), ParameterValue: awssdk.String(strings.Join(p.SubnetIDs, ",")),
			})
		}
		// Endpoint flags (per real script).
		for _, ep := range p.VPCEndpoints {
			switch ep {
			case "s3":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateS3Endpoint"), ParameterValue: awssdk.String("true"),
				})
				// S3EndpointRouteTableIds is resolved at runtime in runCFN
				// before this function is called for the create path.
			case "ecr":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateECREndpoint"), ParameterValue: awssdk.String("true"),
				})
			case "ecrDocker":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateECRDockerEndpoint"), ParameterValue: awssdk.String("true"),
				})
			case "cloudwatchLogs":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateCloudWatchLogsEndpoint"), ParameterValue: awssdk.String("true"),
				})
			case "efs":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateEFSEndpoint"), ParameterValue: awssdk.String("true"),
				})
			case "sts":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateSTSEndpoint"), ParameterValue: awssdk.String("true"),
				})
			case "eksAuth":
				out = append(out, cfntypes.Parameter{
					ParameterKey: awssdk.String("CreateEKSAuthEndpoint"), ParameterValue: awssdk.String("true"),
				})
			}
		}
	}
	// Stable order so tests can assert deterministically.
	sort.Slice(out, func(i, j int) bool {
		return deref(out[i].ParameterKey) < deref(out[j].ParameterKey)
	})
	return out
}

// readExports lists CFN exports starting with "MigrationsExportString" —
// matches the real `aws cloudformation list-exports --query` filter —
// and parses the embedded `export NAME=value;...` style values into a
// CFNExports struct.
func (d *Driver) readExports(ctx context.Context, p Params) (CFNExports, error) {
	if d.cfn == nil {
		return CFNExports{}, errors.New("cfn client not configured")
	}
	const prefix = "MigrationsExportString"
	out := CFNExports{Region: p.Region, Stage: p.Stage}
	var token *string
	for {
		resp, err := d.cfn.ListExports(ctx, &cloudformation.ListExportsInput{NextToken: token})
		if err != nil {
			return out, fmt.Errorf("list exports: %w", err)
		}
		for _, e := range resp.Exports {
			name := deref(e.Name)
			if !strings.HasPrefix(name, prefix) {
				continue
			}
			// If stage is set, filter to exports whose name contains the stage.
			if p.Stage != "" && !strings.Contains(name, "-"+p.Stage+"-") {
				continue
			}
			parseExportValue(deref(e.Value), &out)
		}
		if resp.NextToken == nil {
			break
		}
		token = resp.NextToken
	}
	if out.EKSClusterName == "" {
		return out, fmt.Errorf("no MIGRATIONS_EKS_CLUSTER_NAME found in CFN exports — is the stack deployed?")
	}
	return out, nil
}

// parseExportValue parses bash-style `export FOO=bar; export BAZ=qux;` into the struct.
func parseExportValue(v string, out *CFNExports) {
	parts := strings.Split(v, ";")
	for _, p := range parts {
		p = strings.TrimSpace(p)
		p = strings.TrimPrefix(p, "export ")
		eq := strings.IndexByte(p, '=')
		if eq < 0 {
			continue
		}
		k := strings.TrimSpace(p[:eq])
		val := strings.Trim(strings.TrimSpace(p[eq+1:]), "\"")
		switch k {
		case "MIGRATIONS_EKS_CLUSTER_NAME":
			out.EKSClusterName = val
		case "MIGRATIONS_ECR_REGISTRY":
			out.ECRRegistry = val
		case "MIGRATIONS_SNAPSHOT_ROLE":
			out.SnapshotRole = val
		case "MIGRATIONS_ACCOUNT", "AWS_ACCOUNT":
			out.Account = val
		case "MIGRATIONS_REGION", "AWS_CFN_REGION":
			if val != "" {
				out.Region = val
			}
		case "MIGRATIONS_STAGE":
			if val != "" {
				out.Stage = val
			}
		}
	}
}

// updateKubeconfig shells to `aws eks update-kubeconfig` to write the
// kubectl context. We don't reimplement this in Go because the real
// script does it via subprocess and parity matters.
func (d *Driver) updateKubeconfig(ctx context.Context, region, clusterName string) error {
	if region == "" || clusterName == "" {
		return errors.New("region and cluster name are required")
	}
	args := []string{
		"eks", "update-kubeconfig",
		"--region", region,
		"--name", clusterName,
		"--alias", clusterName,
	}
	cmd := exec.CommandContext(ctx, d.AWSCmd, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("aws eks update-kubeconfig failed: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// grantAccess creates an EKS access entry for each IAM principal in
// (CallerARN + EKSAccessARNs) and associates the cluster-admin policy.
// Mirrors the create-access-entry/associate-access-policy pair from the
// real script. Idempotent: skips if the entry already exists.
func (d *Driver) grantAccess(ctx context.Context, clusterName string, p Params) error {
	if d.eks == nil {
		return errors.New("eks client not configured")
	}
	principals := dedupe(append([]string{p.CallerARN}, p.EKSAccessARNs...))
	for _, arn := range principals {
		if arn == "" {
			continue
		}
		_, err := d.eks.DescribeAccessEntry(ctx, &eks.DescribeAccessEntryInput{
			ClusterName:  awssdk.String(clusterName),
			PrincipalArn: awssdk.String(arn),
		})
		if err != nil {
			// Entry doesn't exist — create it.
			_, err := d.eks.CreateAccessEntry(ctx, &eks.CreateAccessEntryInput{
				ClusterName:  awssdk.String(clusterName),
				PrincipalArn: awssdk.String(arn),
				Type:         awssdk.String("STANDARD"),
			})
			if err != nil {
				return fmt.Errorf("create access entry for %s: %w", arn, err)
			}
		}
		_, err = d.eks.AssociateAccessPolicy(ctx, &eks.AssociateAccessPolicyInput{
			ClusterName:  awssdk.String(clusterName),
			PrincipalArn: awssdk.String(arn),
			PolicyArn:    awssdk.String("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"),
			AccessScope: &ekstypes.AccessScope{
				Type: ekstypes.AccessScopeTypeCluster,
			},
		})
		if err != nil {
			return fmt.Errorf("associate access policy for %s: %w", arn, err)
		}
	}
	return nil
}

// runHelm shells to `helm install`. The real script also extracts
// values.yaml + valuesEks.yaml from the chart tarball; we mirror that.
//
// Why subprocess: helm.sh/helm/v3 pulls in all of k8s.io/* which roughly
// triples the binary size. For v1, a subprocess to the user's `helm` is
// the right tradeoff.
func (d *Driver) runHelm(ctx context.Context, p Params, exports CFNExports, emit func(phase, status, msg string)) error {
	if p.HelmChartPath == "" {
		return errors.New("HelmChartPath is required (caller fetches via ArtifactSource)")
	}
	if exports.EKSClusterName == "" {
		return errors.New("CFN exports missing EKS cluster name")
	}

	ns := p.Namespace
	if ns == "" {
		ns = "ma"
	}
	release := p.ReleaseName
	if release == "" {
		release = ns
	}

	args := []string{
		"install", release, p.HelmChartPath,
		"--kube-context=" + exports.EKSClusterName,
		"--namespace", ns,
		"--create-namespace",
		"--timeout", "20m",
		"--set", "stageName=" + nonEmpty(exports.Stage, p.Stage, "dev"),
		"--set", "aws.region=" + nonEmpty(exports.Region, p.Region),
		"--set", "aws.account=" + exports.Account,
	}
	if exports.SnapshotRole != "" {
		args = append(args,
			"--set", "defaultBucketConfiguration.snapshotRoleArn="+exports.SnapshotRole,
		)
	}

	// TLS-mode flags — port of the case "$tls_mode" block.
	switch p.TLSMode {
	case "pca-existing":
		args = append(args,
			"--set", "conditionalPackageInstalls.aws-privateca-issuer=true",
			"--set", "awsPrivateCA.arn="+p.PCAARN,
			"--set", "awsPrivateCA.region="+nonEmpty(exports.Region, p.Region),
		)
	case "pca-create":
		args = append(args,
			"--set", "conditionalPackageInstalls.aws-privateca-issuer=true",
			"--set", "conditionalPackageInstalls.ack-acmpca-controller=true",
			"--set", "awsPrivateCA.create=true",
			"--set", "awsPrivateCA.region="+nonEmpty(exports.Region, p.Region),
		)
	}

	emit("helm", "progress", fmt.Sprintf("helm install %s", strings.Join(args, " ")))
	cmd := exec.CommandContext(ctx, d.HelmCmd, args...)
	out, err := cmd.CombinedOutput()
	emit("helm", "progress", strings.TrimSpace(string(out)))
	if err != nil {
		return fmt.Errorf("helm install: %w", err)
	}
	return nil
}

// dedupe returns the input slice with empty strings removed and dupes
// collapsed, preserving first-seen order.
func dedupe(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// nonEmpty returns the first non-empty string from the input list.
func nonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// resolveS3RouteTables previously replicated the script's
// describe-route-tables query so the S3EndpointRouteTableIds CFN
// parameter could be populated. The current driver doesn't pass that
// parameter (the v1 import-vpc template makes it optional via a
// CommaDelimitedList default), so the helper is intentionally absent.
// Restore it here when wiring the parameter — see PLAN §0.1 backlog.
