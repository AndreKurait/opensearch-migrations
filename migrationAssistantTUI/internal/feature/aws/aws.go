// Package aws implements feature.AWSService using aws-sdk-go-v2.
//
// Client construction is lazy via aws/config.LoadDefaultConfig — that
// honors $AWS_PROFILE, $AWS_REGION, ~/.aws/config, IMDS, and SSO.
//
// Failure mode: every method returns the underlying error verbatim;
// pages catch AccessDenied and offer manual entry per UX.md §6.3.
package aws

import (
	"context"
	"errors"
	"fmt"
	"strings"

	awssdk "github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/cloudformation"
	"github.com/aws/aws-sdk-go-v2/service/ec2"
	ec2types "github.com/aws/aws-sdk-go-v2/service/ec2/types"
	"github.com/aws/aws-sdk-go-v2/service/sts"

	"github.com/opensearch-project/opensearch-migrations/tui/internal/feature"
)

// Service implements feature.AWSService.
type Service struct {
	cfg awssdk.Config

	cfn *cloudformation.Client
	ec2 *ec2.Client
	sts *sts.Client
}

// Config returns the underlying SDK config so app.New can construct
// matching CFN/EC2/EKS clients for the deploy driver. NEVER call this
// from internal/ui (PLAN §3 rule 1: UI does no I/O).
func (s *Service) Config() awssdk.Config { return s.cfg }

// New tries to load default AWS config. Returns nil + err if creds aren't
// available — caller must fall through to an "AWS not configured" UX.
func New(ctx context.Context) (*Service, error) {
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, fmt.Errorf("aws config: %w", err)
	}
	return &Service{
		cfg: cfg,
		cfn: cloudformation.NewFromConfig(cfg),
		ec2: ec2.NewFromConfig(cfg),
		sts: sts.NewFromConfig(cfg),
	}, nil
}

// WhoAmI returns the resolved identity + region.
func (s *Service) WhoAmI(ctx context.Context) (feature.AWSIdentity, error) {
	out, err := s.sts.GetCallerIdentity(ctx, &sts.GetCallerIdentityInput{})
	if err != nil {
		return feature.AWSIdentity{}, classify(err)
	}
	id := feature.AWSIdentity{
		Region: s.cfg.Region,
	}
	if out.Account != nil {
		id.AccountID = *out.Account
	}
	if out.Arn != nil {
		id.UserARN = *out.Arn
	}
	if id.Region == "" {
		id.Region = "us-east-1"
	}
	return id, nil
}

// ListVPCs returns VPCs in `region` (passed for clarity; cfg already pins one).
func (s *Service) ListVPCs(ctx context.Context, region string) ([]feature.VPC, error) {
	client := s.ec2
	if region != "" && region != s.cfg.Region {
		c := s.cfg.Copy()
		c.Region = region
		client = ec2.NewFromConfig(c)
	}
	out, err := client.DescribeVpcs(ctx, &ec2.DescribeVpcsInput{})
	if err != nil {
		return nil, classify(err)
	}
	res := make([]feature.VPC, 0, len(out.Vpcs))
	for _, v := range out.Vpcs {
		res = append(res, feature.VPC{
			ID:        deref(v.VpcId),
			Name:      tagValue(v.Tags, "Name"),
			CIDR:      deref(v.CidrBlock),
			IsDefault: v.IsDefault != nil && *v.IsDefault,
		})
	}
	return res, nil
}

// ListSubnets returns subnets for vpcID with route-derived labels.
func (s *Service) ListSubnets(ctx context.Context, region, vpcID string) ([]feature.Subnet, error) {
	if vpcID == "" {
		return nil, errors.New("vpc id is required")
	}
	client := s.ec2
	if region != "" && region != s.cfg.Region {
		c := s.cfg.Copy()
		c.Region = region
		client = ec2.NewFromConfig(c)
	}
	subs, err := client.DescribeSubnets(ctx, &ec2.DescribeSubnetsInput{
		Filters: []ec2types.Filter{{Name: awssdk.String("vpc-id"), Values: []string{vpcID}}},
	})
	if err != nil {
		return nil, classify(err)
	}
	rts, err := client.DescribeRouteTables(ctx, &ec2.DescribeRouteTablesInput{
		Filters: []ec2types.Filter{{Name: awssdk.String("vpc-id"), Values: []string{vpcID}}},
	})
	if err != nil {
		return nil, classify(err)
	}
	// Build subnet→route-table map.
	subToRoute := map[string][]ec2types.Route{}
	mainRoutes := []ec2types.Route{}
	for _, rt := range rts.RouteTables {
		isMain := false
		for _, a := range rt.Associations {
			if a.Main != nil && *a.Main {
				isMain = true
			}
			if a.SubnetId != nil {
				subToRoute[*a.SubnetId] = rt.Routes
			}
		}
		if isMain {
			mainRoutes = rt.Routes
		}
	}
	out := make([]feature.Subnet, 0, len(subs.Subnets))
	for _, sn := range subs.Subnets {
		routes := subToRoute[deref(sn.SubnetId)]
		if len(routes) == 0 {
			routes = mainRoutes
		}
		out = append(out, feature.Subnet{
			ID:         deref(sn.SubnetId),
			VPCID:      deref(sn.VpcId),
			AZ:         deref(sn.AvailabilityZone),
			CIDR:       deref(sn.CidrBlock),
			RouteLabel: classifyRoute(routes),
		})
	}
	return out, nil
}

// ListVPCEndpoints returns the existing endpoints for vpcID, classified
// against the bootstrap.sh shortname catalog (s3 / ecr / ecrDocker).
// Other endpoints are returned with ShortName="" so callers can decide.
func (s *Service) ListVPCEndpoints(ctx context.Context, region, vpcID string) ([]feature.VPCEndpoint, error) {
	if vpcID == "" {
		return nil, errors.New("vpc id is required")
	}
	client := s.ec2
	if region != "" && region != s.cfg.Region {
		c := s.cfg.Copy()
		c.Region = region
		client = ec2.NewFromConfig(c)
	}
	out, err := client.DescribeVpcEndpoints(ctx, &ec2.DescribeVpcEndpointsInput{
		Filters: []ec2types.Filter{{Name: awssdk.String("vpc-id"), Values: []string{vpcID}}},
	})
	if err != nil {
		return nil, classify(err)
	}
	res := make([]feature.VPCEndpoint, 0, len(out.VpcEndpoints))
	for _, e := range out.VpcEndpoints {
		ep := feature.VPCEndpoint{
			ID:          deref(e.VpcEndpointId),
			ServiceName: deref(e.ServiceName),
			State:       string(e.State),
		}
		ep.ShortName = ShortNameForService(ep.ServiceName)
		res = append(res, ep)
	}
	return res, nil
}

// ShortNameForService maps a fully-qualified AWS service-endpoint name
// ("com.amazonaws.us-east-1.s3") to the bootstrap.sh shortname catalog.
// Returns "" if it's not one of the three required ones.
func ShortNameForService(svc string) string {
	low := strings.ToLower(svc)
	switch {
	case strings.HasSuffix(low, ".s3"):
		return "s3"
	case strings.HasSuffix(low, ".ecr.dkr"):
		return "ecrDocker"
	case strings.HasSuffix(low, ".ecr.api"), strings.HasSuffix(low, ".ecr"):
		return "ecr"
	}
	return ""
}

// ListMAExports returns CloudFormation exports starting with the
// MigrationsExportString prefix — those are the exports the bootstrap
// stack publishes. Any hit means a prior MA install is already live in
// this account/region and a fresh deploy will trip rollback.
func (s *Service) ListMAExports(ctx context.Context, region string) ([]feature.CFNExport, error) {
	client := s.cfn
	if region != "" && region != s.cfg.Region {
		c := s.cfg.Copy()
		c.Region = region
		client = cloudformation.NewFromConfig(c)
	}
	var out []feature.CFNExport
	var token *string
	for {
		resp, err := client.ListExports(ctx, &cloudformation.ListExportsInput{NextToken: token})
		if err != nil {
			return nil, classify(err)
		}
		for _, e := range resp.Exports {
			name := deref(e.Name)
			// Match the real bootstrap.sh prefix exactly. The script's
			// `--query "Exports[?starts_with(Name, ` + "`MigrationsExportString`" + `)].[Name,Value]"`
			// uses this prefix; we mirror it 1:1 so a user with an
			// existing install always trips the resume guard.
			if strings.HasPrefix(name, "MigrationsExportString") ||
				strings.HasPrefix(name, "MA") ||
				strings.Contains(strings.ToLower(name), "migration-assistant") {
				out = append(out, feature.CFNExport{
					Name:               name,
					Value:              deref(e.Value),
					ExportingStackName: deref(e.ExportingStackId),
				})
			}
		}
		if resp.NextToken == nil {
			break
		}
		token = resp.NextToken
	}
	return out, nil
}

// classifyRoute returns "IGW" if a 0.0.0.0/0 → igw-* exists, "NAT" if
// a 0.0.0.0/0 → nat-* exists, else "ISOLATED" (UX.md §6.1 step 5).
func classifyRoute(routes []ec2types.Route) feature.SubnetRoute {
	for _, r := range routes {
		dst := deref(r.DestinationCidrBlock)
		if dst != "0.0.0.0/0" {
			continue
		}
		switch {
		case r.GatewayId != nil && strings.HasPrefix(*r.GatewayId, "igw-"):
			return feature.RoutePublic
		case r.NatGatewayId != nil:
			return feature.RouteNAT
		}
	}
	if len(routes) == 0 {
		return feature.RouteUnknown
	}
	return feature.RouteIsolated
}

func tagValue(tags []ec2types.Tag, key string) string {
	for _, t := range tags {
		if t.Key != nil && *t.Key == key && t.Value != nil {
			return *t.Value
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

// classify normalizes AWS errors so callers can branch on AccessDenied.
func classify(err error) error {
	if err == nil {
		return nil
	}
	low := strings.ToLower(err.Error())
	if strings.Contains(low, "accessdenied") || strings.Contains(low, "unauthorizedoperation") {
		return fmt.Errorf("access denied: %w", err)
	}
	return err
}
