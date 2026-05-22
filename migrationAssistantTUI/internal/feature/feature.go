// Package feature defines the domain service contracts the TUI consumes
// through Workspace (PLAN §4.2). Implementations live in feature/<name>/
// and import AWS SDK / kubectl / helm bindings; the UI sees only these
// interfaces, which makes pages trivially mockable in tests.
//
// Style rule (PLAN §3 rule 2): nothing in this package may import
// charm.land/...; it's pure domain.
package feature

import (
	"context"
	"time"
)

// AWSIdentity is the result of a sts:GetCallerIdentity call (UX.md §6.1).
type AWSIdentity struct {
	AccountID string
	UserARN   string
	Region    string
	Profile   string // resolved profile name, "default" if unset
}

// VPC is one row in the VPC picker (UX.md §8.1).
type VPC struct {
	ID    string
	Name  string
	CIDR  string
	IsDefault bool
}

// Subnet is one row in the subnet picker. RouteLabel is "IGW (public)",
// "NAT", or "ISOLATED" — derived from route tables (UX.md §6.1 step 5).
type Subnet struct {
	ID         string
	VPCID      string
	AZ         string
	CIDR       string
	RouteLabel SubnetRoute
}

type SubnetRoute string

const (
	RoutePublic   SubnetRoute = "IGW"
	RouteNAT      SubnetRoute = "NAT"
	RouteIsolated SubnetRoute = "ISOLATED"
	RouteUnknown  SubnetRoute = "UNKNOWN"
)

// CFNExport is a CloudFormation export entry — used to detect prior MA
// installs (UX.md §6.1 step 3).
type CFNExport struct {
	Name        string
	Value       string
	ExportingStackName string
}

// StagesFromExports extracts the unique stage names from a list of
// MigrationsExportString-<stage>-<region>* exports. Order is the
// first-seen-first order — stable across multiple calls because
// underlying ListExports is deterministic.
func StagesFromExports(exports []CFNExport, region string) []string {
	if region == "" {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	prefix := "MigrationsExportString-"
	regionDash := "-" + region
	for _, e := range exports {
		if len(e.Name) <= len(prefix) || e.Name[:len(prefix)] != prefix {
			continue
		}
		rest := e.Name[len(prefix):]
		idx := indexOf(rest, regionDash)
		if idx <= 0 {
			continue
		}
		stage := rest[:idx]
		if seen[stage] {
			continue
		}
		seen[stage] = true
		out = append(out, stage)
	}
	return out
}

// indexOf is strings.Index inlined so feature/ stays free of stdlib
// transitive deps that would creep into the UI's dep-guard pruning.
func indexOf(haystack, needle string) int {
	n := len(needle)
	if n == 0 {
		return 0
	}
	if n > len(haystack) {
		return -1
	}
	for i := 0; i+n <= len(haystack); i++ {
		if haystack[i:i+n] == needle {
			return i
		}
	}
	return -1
}

// VPCEndpoint is one row in the VPC endpoint detector. Service is the
// AWS service name ("com.amazonaws.us-east-1.s3") and ShortName is the
// suffix ("s3", "ecr", "ecr.dkr") used by the bootstrap.sh
// --create-vpc-endpoints flag.
type VPCEndpoint struct {
	ID        string // vpce-XXXX
	ServiceName string
	ShortName string // "s3" | "ecr" | "ecrDocker"
	State     string
}

// HelmRelease is the abridged result of `helm status` (UX.md §5.1).
type HelmRelease struct {
	Name      string
	Namespace string
	Revision  int
	Status    string
	Updated   time.Time
	ChartName string
	ChartVer  string
}

// AgentCLI describes one detected agent binary (UX.md §0.6).
type AgentCLI struct {
	Name          string // "claude-code", "kiro-cli"
	Path          string // absolute path on PATH, "" if not installed
	LocalVersion  string // raw `--version` output, "" if unknown
	LatestVersion string // upstream version, "" if unknown / skipped
	BehindBy      VersionDelta
}

// VersionDelta classifies the gap between local and latest. None when
// local==latest, Patch when only patch differs, etc. PLAN §0.6.
type VersionDelta string

const (
	DeltaNone    VersionDelta = ""
	DeltaPatch   VersionDelta = "patch"
	DeltaMinor   VersionDelta = "minor"
	DeltaMajor   VersionDelta = "major"
	DeltaAhead   VersionDelta = "ahead"
	DeltaUnknown VersionDelta = "unknown"
)

// AWSService groups every AWS query the TUI performs. All methods accept
// a context to cooperate with §9.3 publisher rules.
type AWSService interface {
	WhoAmI(ctx context.Context) (AWSIdentity, error)
	ListVPCs(ctx context.Context, region string) ([]VPC, error)
	ListSubnets(ctx context.Context, region, vpcID string) ([]Subnet, error)
	ListMAExports(ctx context.Context, region string) ([]CFNExport, error)
	ListVPCEndpoints(ctx context.Context, region, vpcID string) ([]VPCEndpoint, error)
}

// HelmService isolates the helm operations we need (PLAN §0.1 — helm
// install is the only client-driven phase of bootstrap.sh; we use Go
// helm bindings, not a subprocess).
type HelmService interface {
	Status(ctx context.Context, kubeContext, namespace, release string) (HelmRelease, error)
	Install(ctx context.Context, p HelmInstallParams) (HelmRelease, error)
	Uninstall(ctx context.Context, kubeContext, namespace, release string) error
}

// HelmInstallParams captures the inputs to `helm install
// migration-assistant <chart>` (bootstrap.sh §helm phase).
type HelmInstallParams struct {
	KubeContext string
	Namespace   string
	ReleaseName string
	ChartPath   string
	ValuesFiles []string
	SetValues   map[string]string
}

// AgentDetector locates installed agent CLIs on PATH and queries upstream
// for their latest version (UX.md §0.6).
type AgentDetector interface {
	Detect(ctx context.Context) ([]AgentCLI, error)
}

// ArtifactSource fetches release / raw-repo artifacts per UX.md §0.7.
//
// Order: GitHub release asset → tag-pinned raw repo → hard fail.
type ArtifactSource interface {
	// FetchAtTag downloads `name` at `tag`, writes it to dst.
	FetchAtTag(ctx context.Context, tag, name, dst string) (sourceUsed string, err error)
}
