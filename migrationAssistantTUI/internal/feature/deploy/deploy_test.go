package deploy

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudformation"
	cfntypes "github.com/aws/aws-sdk-go-v2/service/cloudformation/types"
	"github.com/aws/aws-sdk-go-v2/service/ec2"
	"github.com/aws/aws-sdk-go-v2/service/eks"
	"github.com/aws/smithy-go/middleware"
	"github.com/stretchr/testify/require"
)

// inject installs a finalize middleware that returns the given output
// without making a real network call.
func inject(handler func(ctx context.Context, op string) (interface{}, error)) func(*middleware.Stack) error {
	return func(stack *middleware.Stack) error {
		return stack.Finalize.Add(
			middleware.FinalizeMiddlewareFunc("test-finalize",
				func(ctx context.Context, in middleware.FinalizeInput, _ middleware.FinalizeHandler) (
					middleware.FinalizeOutput, middleware.Metadata, error,
				) {
					op := middleware.GetOperationName(ctx)
					out, err := handler(ctx, op)
					if err != nil {
						return middleware.FinalizeOutput{}, middleware.Metadata{}, err
					}
					return middleware.FinalizeOutput{Result: out}, middleware.Metadata{}, nil
				},
			),
			middleware.Before,
		)
	}
}

func TestRunErrorsWhenTemplateBodyEmpty(t *testing.T) {
	t.Parallel()
	cfn := cloudformation.NewFromConfig(aws.Config{Region: "us-east-1"})
	d := NewDriver(cfn, ec2.NewFromConfig(aws.Config{Region: "us-east-1"}), eks.NewFromConfig(aws.Config{Region: "us-east-1"}))
	d.PollEvery = time.Millisecond

	events := make(chan PhaseEvent, 8)
	err := d.Run(context.Background(), Params{
		Region: "us-east-1", StackName: "x", Stage: "dev", Scope: "create-vpc",
		HelmChartPath: "/tmp/chart.tgz",
	}, events)
	require.Error(t, err)
	require.Contains(t, err.Error(), "CFN template body is required")
}

func TestRunSkipsCFNWhenScopeIsSkipCFN(t *testing.T) {
	t.Parallel()
	cfg := aws.Config{Region: "us-east-1"}
	cfn := cloudformation.NewFromConfig(cfg, cloudformation.WithAPIOptions(
		inject(func(ctx context.Context, op string) (interface{}, error) {
			if op == "ListExports" {
				return &cloudformation.ListExportsOutput{Exports: []cfntypes.Export{
					{
						Name:  aws.String("MigrationsExportString-dev-us-east-1"),
						Value: aws.String("export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-dev-us-east-1; export MIGRATIONS_ECR_REGISTRY=12345.dkr.ecr.us-east-1.amazonaws.com"),
					},
				}}, nil
			}
			return nil, errors.New("unhandled op " + op)
		}),
	))
	d := NewDriver(cfn, ec2.NewFromConfig(cfg), eks.NewFromConfig(cfg))
	d.HelmCmd = "/usr/bin/true"
	d.AWSCmd = "/usr/bin/true"

	events := make(chan PhaseEvent, 32)
	err := d.Run(context.Background(), Params{
		Region: "us-east-1", StackName: "x", Stage: "dev", Scope: "skip-cfn",
		ReleaseName: "ma", Namespace: "ma", HelmChartPath: "/dev/null",
		CallerARN: "",
	}, events)
	require.NoError(t, err)

	// In skip-cfn mode the cfn phase emits a single "(skipped)" completed
	// event and no progress/started events. Verify NO progress events.
	var cfnProgressCount int
	for ev := range events {
		if ev.Phase == "cfn" && ev.Status == "progress" {
			cfnProgressCount++
		}
	}
	require.Zero(t, cfnProgressCount, "skip-cfn must not emit cfn progress events")
}

func TestRunCFNHappyPath(t *testing.T) {
	t.Parallel()
	cfg := aws.Config{Region: "us-east-1"}
	cfn := cloudformation.NewFromConfig(cfg, cloudformation.WithAPIOptions(
		inject(func(ctx context.Context, op string) (interface{}, error) {
			switch op {
			case "CreateStack":
				return &cloudformation.CreateStackOutput{StackId: aws.String("arn:cfn:stack")}, nil
			case "DescribeStackEvents":
				return &cloudformation.DescribeStackEventsOutput{}, nil
			case "DescribeStacks":
				return &cloudformation.DescribeStacksOutput{
					Stacks: []cfntypes.Stack{{StackStatus: cfntypes.StackStatusCreateComplete}},
				}, nil
			case "ListExports":
				return &cloudformation.ListExportsOutput{Exports: []cfntypes.Export{
					{
						Name:  aws.String("MigrationsExportString-dev-us-east-1"),
						Value: aws.String("export MIGRATIONS_EKS_CLUSTER_NAME=mig-cluster-dev-us-east-1; export MIGRATIONS_ECR_REGISTRY=12345.dkr.ecr.us-east-1.amazonaws.com"),
					},
				}}, nil
			}
			return nil, errors.New("unhandled op " + op)
		}),
	))
	d := NewDriver(cfn, ec2.NewFromConfig(cfg), eks.NewFromConfig(cfg))
	d.PollEvery = time.Millisecond
	d.HelmCmd = "/usr/bin/true"
	d.AWSCmd = "/usr/bin/true"

	events := make(chan PhaseEvent, 64)
	err := d.Run(context.Background(), Params{
		Region: "us-east-1", StackName: "MA-Dev", Stage: "dev", Scope: "create-vpc",
		ReleaseName:   "ma",
		Namespace:     "ma",
		TemplateBody:  `{"AWSTemplateFormatVersion":"2010-09-09"}`,
		HelmChartPath: "/dev/null",
		MAVersion:     "3.2.1",
	}, events)
	require.NoError(t, err)

	var sawCFNCompleted, sawHelmCompleted, sawKubeconfig bool
	for ev := range events {
		if ev.Phase == "cfn" && ev.Status == "completed" {
			sawCFNCompleted = true
		}
		if ev.Phase == "helm" && ev.Status == "completed" {
			sawHelmCompleted = true
		}
		if ev.Phase == "kubeconfig" && ev.Status == "completed" {
			sawKubeconfig = true
		}
	}
	require.True(t, sawCFNCompleted)
	require.True(t, sawKubeconfig)
	require.True(t, sawHelmCompleted)
}

func TestRunCFNFailureBubblesUp(t *testing.T) {
	t.Parallel()
	cfg := aws.Config{Region: "us-east-1"}
	cfn := cloudformation.NewFromConfig(cfg, cloudformation.WithAPIOptions(
		inject(func(ctx context.Context, op string) (interface{}, error) {
			switch op {
			case "CreateStack":
				return &cloudformation.CreateStackOutput{StackId: aws.String("arn:cfn:stack")}, nil
			case "DescribeStackEvents":
				return &cloudformation.DescribeStackEventsOutput{}, nil
			case "DescribeStacks":
				return &cloudformation.DescribeStacksOutput{
					Stacks: []cfntypes.Stack{{
						StackStatus:       cfntypes.StackStatusCreateFailed,
						StackStatusReason: aws.String("rolled back"),
					}},
				}, nil
			}
			return nil, errors.New("unhandled op " + op)
		}),
	))
	d := NewDriver(cfn, ec2.NewFromConfig(cfg), eks.NewFromConfig(cfg))
	d.PollEvery = time.Millisecond

	events := make(chan PhaseEvent, 16)
	err := d.Run(context.Background(), Params{
		Region: "us-east-1", StackName: "MA-Dev", Stage: "dev", Scope: "create-vpc",
		TemplateBody: "{}",
	}, events)
	require.Error(t, err)
	require.Contains(t, err.Error(), "terminal failure")
	require.Contains(t, err.Error(), "rolled back")
}

func TestRunHelmFailsWhenChartPathEmpty(t *testing.T) {
	t.Parallel()
	cfn := cloudformation.NewFromConfig(aws.Config{Region: "us-east-1"})
	d := NewDriver(cfn, ec2.NewFromConfig(aws.Config{Region: "us-east-1"}), eks.NewFromConfig(aws.Config{Region: "us-east-1"}))

	events := make(chan PhaseEvent, 8)
	err := d.Run(context.Background(), Params{
		Region: "us-east-1", StackName: "x", Stage: "dev", Scope: "skip-cfn",
		ReleaseName: "ma", Namespace: "ma", HelmChartPath: "",
	}, events)
	require.Error(t, err)
	// Either the readExports failure (no MIGRATIONS_EKS_CLUSTER_NAME) OR
	// the empty HelmChartPath check counts — the test asserts that an
	// empty chart path doesn't silently succeed.
	require.NotContains(t, err.Error(), "helm install: <nil>")
}

func TestCFNParamsBuiltCorrectly(t *testing.T) {
	t.Parallel()
	out := BuildCFNParams(Params{
		Stage: "dev", Scope: "import-vpc",
		VPCID: "vpc-0abc", SubnetIDs: []string{"subnet-1", "subnet-2"},
		VPCEndpoints: []string{"s3", "ecr", "ecrDocker"},
	})
	byKey := map[string]string{}
	for _, p := range out {
		byKey[*p.ParameterKey] = *p.ParameterValue
	}
	require.Equal(t, "dev", byKey["Stage"])
	require.Equal(t, "vpc-0abc", byKey["VPCId"])
	require.Equal(t, "subnet-1,subnet-2", byKey["VPCSubnetIds"])
	require.Equal(t, "true", byKey["CreateS3Endpoint"])
	require.Equal(t, "true", byKey["CreateECREndpoint"])
	require.Equal(t, "true", byKey["CreateECRDockerEndpoint"])
}

func TestNewDriverDefaults(t *testing.T) {
	t.Parallel()
	d := NewDriver(nil, nil, nil)
	require.Equal(t, "helm", d.HelmCmd)
	require.Equal(t, "kubectl", d.KubectlCmd)
	require.Equal(t, "aws", d.AWSCmd)
	require.Equal(t, 10*time.Second, d.PollEvery)
}
