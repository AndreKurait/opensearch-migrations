package review_test

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/review"
	"github.com/opensearch-project/opensearch-migrations/tui/internal/ui/pages/wizard"
)

func TestBuildArgvImportVPC(t *testing.T) {
	t.Parallel()
	st := wizard.State{
		Region:       "us-east-1",
		Stage:        "dev",
		Scope:        "import-vpc",
		StackName:    "MA-Dev",
		VPCID:        "vpc-0def",
		SubnetIDs:    []string{"subnet-222", "subnet-333"},
		VPCEndpoints: []string{"s3", "ecr", "ecrDocker"},
		Source:       "published",
		Version:      "3.2.1",
		TLSMode:      "self-signed",
		EKSAccessARN: "arn:aws:iam::1:user/Admin",
	}
	got := review.BuildArgv(st)
	for _, want := range []string{
		"aws-bootstrap.sh",
		"--deploy-import-vpc-cfn",
		"--stack-name MA-Dev",
		"--stage dev",
		"--region us-east-1",
		"--vpc-id vpc-0def",
		"--subnet-ids subnet-222,subnet-333",
		"--create-vpc-endpoints s3,ecr,ecrDocker",
		"--version 3.2.1",
		"--tls-mode self-signed",
		"--eks-access-principal-arn arn:aws:iam::1:user/Admin",
	} {
		require.True(t, strings.Contains(got, want),
			"argv missing %q in:\n%s", want, got)
	}
}

func TestBuildArgvCreateVPCDefaults(t *testing.T) {
	t.Parallel()
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		Source: "published", TLSMode: "self-signed",
	}
	got := review.BuildArgv(st)
	require.Contains(t, got, "--deploy-create-vpc-cfn")
	require.Contains(t, got, "--version latest")
}

func TestBuildArgvBuildFromSource(t *testing.T) {
	t.Parallel()
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "create-vpc",
		Source: "build", TLSMode: "self-signed",
	}
	got := review.BuildArgv(st)
	require.Contains(t, got, "--build")
	require.NotContains(t, got, "--version")
}

func TestBuildArgvSkipCFN(t *testing.T) {
	t.Parallel()
	st := wizard.State{
		Region: "us-east-1", Stage: "dev", Scope: "skip-cfn",
		Source: "published", TLSMode: "none",
	}
	got := review.BuildArgv(st)
	require.Contains(t, got, "--skip-cfn-deploy")
	require.Contains(t, got, "--tls-mode none")
}
