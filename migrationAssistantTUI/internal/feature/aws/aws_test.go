package aws

import (
	"context"
	"errors"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudformation"
	cfntypes "github.com/aws/aws-sdk-go-v2/service/cloudformation/types"
	"github.com/aws/aws-sdk-go-v2/service/ec2"
	ec2types "github.com/aws/aws-sdk-go-v2/service/ec2/types"
	"github.com/aws/aws-sdk-go-v2/service/sts"
	"github.com/aws/smithy-go/middleware"
	"github.com/stretchr/testify/require"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// inject installs a finalize middleware that returns the given output
// without making a real network call. This is the canonical pattern for
// testing aws-sdk-go-v2 services without LocalStack.
func inject(out interface{}) func(*middleware.Stack) error {
	return func(stack *middleware.Stack) error {
		return stack.Finalize.Add(
			middleware.FinalizeMiddlewareFunc("test-finalize",
				func(ctx context.Context, in middleware.FinalizeInput, _ middleware.FinalizeHandler) (
					middleware.FinalizeOutput, middleware.Metadata, error,
				) {
					return middleware.FinalizeOutput{Result: out}, middleware.Metadata{}, nil
				},
			),
			middleware.Before,
		)
	}
}

// injectError returns an error from the finalize step.
func injectError(err error) func(*middleware.Stack) error {
	return func(stack *middleware.Stack) error {
		return stack.Finalize.Add(
			middleware.FinalizeMiddlewareFunc("test-finalize-err",
				func(ctx context.Context, in middleware.FinalizeInput, _ middleware.FinalizeHandler) (
					middleware.FinalizeOutput, middleware.Metadata, error,
				) {
					return middleware.FinalizeOutput{}, middleware.Metadata{}, err
				},
			),
			middleware.Before,
		)
	}
}

func newTestService(t *testing.T) *Service {
	t.Helper()
	cfg := aws.Config{Region: "us-east-1"}
	return &Service{
		cfg: cfg,
		cfn: cloudformation.NewFromConfig(cfg),
		ec2: ec2.NewFromConfig(cfg),
		sts: sts.NewFromConfig(cfg),
	}
}

func TestWhoAmIReturnsIdentity(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	cfg := s.cfg.Copy()
	s.sts = sts.NewFromConfig(cfg, sts.WithAPIOptions(
		inject(&sts.GetCallerIdentityOutput{
			Account: aws.String("123456789012"),
			Arn:     aws.String("arn:aws:iam::123456789012:user/Admin"),
		}),
	))
	id, err := s.WhoAmI(context.Background())
	require.NoError(t, err)
	require.Equal(t, "123456789012", id.AccountID)
	require.Equal(t, "us-east-1", id.Region)
	require.Contains(t, id.UserARN, "Admin")
}

func TestWhoAmIClassifiesAccessDenied(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	cfg := s.cfg.Copy()
	s.sts = sts.NewFromConfig(cfg, sts.WithAPIOptions(
		injectError(errors.New("AccessDenied: not allowed")),
	))
	_, err := s.WhoAmI(context.Background())
	require.Error(t, err)
	require.Contains(t, err.Error(), "access denied")
}

func TestListVPCsExtractsTagsAndCIDR(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	cfg := s.cfg.Copy()
	s.ec2 = ec2.NewFromConfig(cfg, ec2.WithAPIOptions(
		inject(&ec2.DescribeVpcsOutput{
			Vpcs: []ec2types.Vpc{
				{
					VpcId:     aws.String("vpc-0abc"),
					CidrBlock: aws.String("10.0.0.0/16"),
					IsDefault: aws.Bool(false),
					Tags:      []ec2types.Tag{{Key: aws.String("Name"), Value: aws.String("prod-vpc")}},
				},
				{
					VpcId:     aws.String("vpc-default"),
					CidrBlock: aws.String("172.31.0.0/16"),
					IsDefault: aws.Bool(true),
				},
			},
		}),
	))
	out, err := s.ListVPCs(context.Background(), "us-east-1")
	require.NoError(t, err)
	require.Len(t, out, 2)
	require.Equal(t, "vpc-0abc", out[0].ID)
	require.Equal(t, "prod-vpc", out[0].Name)
	require.Equal(t, "10.0.0.0/16", out[0].CIDR)
	require.False(t, out[0].IsDefault)
	require.True(t, out[1].IsDefault)
}

func TestListSubnetsRequiresVPCID(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	_, err := s.ListSubnets(context.Background(), "us-east-1", "")
	require.Error(t, err)
	require.Contains(t, err.Error(), "vpc id is required")
}

func TestListSubnetsClassifiesRoutes(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	cfg := s.cfg.Copy()

	// We need to inject DIFFERENT outputs for DescribeSubnets and DescribeRouteTables.
	// The finalize middleware fires for every request, so route by request type.
	s.ec2 = ec2.NewFromConfig(cfg, ec2.WithAPIOptions(
		func(stack *middleware.Stack) error {
			return stack.Finalize.Add(
				middleware.FinalizeMiddlewareFunc("test-route-finalize",
					func(ctx context.Context, in middleware.FinalizeInput, _ middleware.FinalizeHandler) (
						middleware.FinalizeOutput, middleware.Metadata, error,
					) {
						switch in.Request.(type) {
						case *ec2.DescribeSubnetsInput:
							// Fall through; we type-switch on the DESERIALIZED operation context instead.
						}
						// Use the operation name from the context (set by RegisterServiceMetadata).
						op := middleware.GetOperationName(ctx)
						switch op {
						case "DescribeSubnets":
							return middleware.FinalizeOutput{Result: &ec2.DescribeSubnetsOutput{
								Subnets: []ec2types.Subnet{
									{SubnetId: aws.String("subnet-pub"), VpcId: aws.String("vpc-x"), AvailabilityZone: aws.String("us-east-1a"), CidrBlock: aws.String("10.0.1.0/24")},
									{SubnetId: aws.String("subnet-nat"), VpcId: aws.String("vpc-x"), AvailabilityZone: aws.String("us-east-1b"), CidrBlock: aws.String("10.0.2.0/24")},
									{SubnetId: aws.String("subnet-iso"), VpcId: aws.String("vpc-x"), AvailabilityZone: aws.String("us-east-1c"), CidrBlock: aws.String("10.0.3.0/24")},
								},
							}}, middleware.Metadata{}, nil
						case "DescribeRouteTables":
							return middleware.FinalizeOutput{Result: &ec2.DescribeRouteTablesOutput{
								RouteTables: []ec2types.RouteTable{
									{
										Associations: []ec2types.RouteTableAssociation{{SubnetId: aws.String("subnet-pub")}},
										Routes: []ec2types.Route{{
											DestinationCidrBlock: aws.String("0.0.0.0/0"),
											GatewayId:            aws.String("igw-abc"),
										}},
									},
									{
										Associations: []ec2types.RouteTableAssociation{{SubnetId: aws.String("subnet-nat")}},
										Routes: []ec2types.Route{{
											DestinationCidrBlock: aws.String("0.0.0.0/0"),
											NatGatewayId:         aws.String("nat-abc"),
										}},
									},
									{
										Associations: []ec2types.RouteTableAssociation{{SubnetId: aws.String("subnet-iso")}},
										Routes: []ec2types.Route{{
											DestinationCidrBlock: aws.String("10.0.0.0/16"),
										}},
									},
								},
							}}, middleware.Metadata{}, nil
						}
						return middleware.FinalizeOutput{}, middleware.Metadata{}, errors.New("unhandled op " + op)
					},
				),
				middleware.Before,
			)
		},
	))

	subs, err := s.ListSubnets(context.Background(), "us-east-1", "vpc-x")
	require.NoError(t, err)
	require.Len(t, subs, 3)

	byID := map[string]feature.Subnet{}
	for _, s := range subs {
		byID[s.ID] = s
	}
	require.Equal(t, feature.RoutePublic, byID["subnet-pub"].RouteLabel)
	require.Equal(t, feature.RouteNAT, byID["subnet-nat"].RouteLabel)
	require.Equal(t, feature.RouteIsolated, byID["subnet-iso"].RouteLabel)
}

func TestListMAExportsFiltersByPrefix(t *testing.T) {
	t.Parallel()
	s := newTestService(t)
	cfg := s.cfg.Copy()
	s.cfn = cloudformation.NewFromConfig(cfg, cloudformation.WithAPIOptions(
		inject(&cloudformation.ListExportsOutput{
			Exports: []cfntypes.Export{
				{Name: aws.String("MigrationsExportString-dev-us-east-1"), Value: aws.String("export X=Y;"), ExportingStackId: aws.String("MA-Dev")},
				{Name: aws.String("MA-Dev-Cluster"), Value: aws.String("arn:aws:eks:..."), ExportingStackId: aws.String("MA-Dev")},
				{Name: aws.String("UnrelatedExport"), Value: aws.String("foo")},
				{Name: aws.String("migration-assistant-vpc"), Value: aws.String("vpc-0abc")},
			},
		}),
	))
	out, err := s.ListMAExports(context.Background(), "us-east-1")
	require.NoError(t, err)
	require.Len(t, out, 1, "must match only MigrationsExportString* — anything else is too loose and would falsely block users with unrelated 'MA' stacks")
	require.Equal(t, "MigrationsExportString-dev-us-east-1", out[0].Name)
}

func TestClassifyRouteUnknown(t *testing.T) {
	t.Parallel()
	require.Equal(t, feature.RouteUnknown, classifyRoute(nil))
}

func TestDerefAndTagValueHelpers(t *testing.T) {
	t.Parallel()
	require.Equal(t, "", deref(nil))
	require.Equal(t, "x", deref(aws.String("x")))

	tags := []ec2types.Tag{
		{Key: aws.String("Name"), Value: aws.String("alpha")},
		{Key: aws.String("Other"), Value: aws.String("beta")},
	}
	require.Equal(t, "alpha", tagValue(tags, "Name"))
	require.Equal(t, "", tagValue(tags, "Missing"))
}

func TestClassifyPassesNilThrough(t *testing.T) {
	t.Parallel()
	require.NoError(t, classify(nil))
}
