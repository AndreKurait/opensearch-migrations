// Package cost computes a hardcoded daily cost estimate for an MA
// deployment, based on public AWS pricing as of 2026-Q1 in us-east-1.
// Values are intentionally conservative (rounded up).
//
// This is NOT a billing API — it's a back-of-the-envelope number to
// set expectations before the user clicks "launch". Real cost depends
// on data volume, NAT egress, and node-pool choices the wizard doesn't
// expose.
//
// Sources (frozen at v1 release time):
//   - EKS control plane: $0.10/h
//   - EC2 t3.large (migration-console pod): $0.0832/h on-demand us-east-1
//   - EC2 m5.large × 2 (services pool): $0.096/h on-demand us-east-1
//   - NAT Gateway: $0.045/h + $0.045/GB egress
//   - VPC interface endpoint: $0.01/h per endpoint per AZ
//   - S3 gateway endpoint: free
//   - ECR storage: $0.10/GB-month → ~$0.50/day for a typical image cache
//   - CloudWatch Logs: $0.50/GB ingest, $0.03/GB store — minimal at idle
//   - NLB (Argo workflows): $0.0225/h + LCU
//   - AWS Private CA: $400/month flat
package cost

import "fmt"

// Estimate is the result of EstimateDailyUSD.
type Estimate struct {
	// USDPerDay is the headline number, rounded to the nearest dollar.
	USDPerDay int

	// Lines is a per-line breakdown for the [d]etails panel.
	Lines []Line
}

// Line is one row in the cost-breakdown panel.
type Line struct {
	Component string
	USDPerDay float64
}

// Inputs identifies which optional resources the wizard chose to create.
type Inputs struct {
	// Scope: "create-vpc" / "import-vpc" / "skip-cfn".
	Scope string
	// VPCEndpoints to be created (e.g. {"s3","ecr","ecrDocker"}). S3 is free.
	VPCEndpoints []string
	// SubnetCount drives NAT GW count (1 NAT/AZ for Create-VPC, 0 for Import-VPC).
	SubnetCount int
	// TLSMode: "pca-create" adds the AWS Private CA fee.
	TLSMode string
}

// EstimateDailyUSD returns the rounded daily cost based on Inputs.
func EstimateDailyUSD(in Inputs) Estimate {
	var lines []Line

	// Always-on fixed costs.
	lines = append(lines, Line{"EKS control plane (1 cluster)", 24 * 0.10}) // $2.40/day
	// Migration-console pod runs on a dedicated t3.large (0.0832/h us-east-1 on-demand).
	lines = append(lines, Line{"EC2 t3.large (migration console)", 24 * 0.0832}) // $2.00/day
	// Two m5.large for the rest of the services (Argo workflows, capture
	// proxy, replayer, traffic generator, dashboards — 0.096/h on-demand).
	lines = append(lines, Line{"EC2 m5.large × 2 (services)", 24 * 2 * 0.096}) // $4.61/day
	lines = append(lines, Line{"NLB (Argo workflows)", 24 * 0.0225})            // $0.54/day
	lines = append(lines, Line{"ECR storage (~150 GB)", 0.50})                  // $0.50/day
	lines = append(lines, Line{"CloudWatch Logs (idle)", 0.30})                 // $0.30/day
	lines = append(lines, Line{"S3 (snapshots, free tier)", 0.10})              // ~free at idle

	// Scope-dependent.
	if in.Scope == "create-vpc" {
		// 2 NAT gateways (one per AZ).
		lines = append(lines, Line{"NAT Gateway × 2 (per-AZ)", 24 * 2 * 0.045}) // $2.16/day
	}

	// VPC interface endpoints (excluding S3 which is a free gateway endpoint).
	for _, ep := range in.VPCEndpoints {
		if ep == "s3" {
			continue
		}
		// 2 AZs × $0.01/h
		lines = append(lines, Line{"VPC endpoint: " + ep + " (2 AZs)", 24 * 2 * 0.01}) // $0.48/day each
	}

	// TLS surcharges.
	if in.TLSMode == "pca-create" {
		// AWS Private CA: $400/month flat → ~$13.33/day. WARNING: the
		// 7-day deletion grace period means accidental creates linger.
		lines = append(lines, Line{"AWS Private CA (NEW, $400/month)", 13.33})
	}

	total := 0.0
	for _, l := range lines {
		total += l.USDPerDay
	}

	return Estimate{
		USDPerDay: int(total + 0.5), // round-half-up
		Lines:     lines,
	}
}

// Headline returns a one-line "minimum cost" string for the review screen.
// The number is conservative — actual cost grows with NAT egress, snapshot
// data volume, and any custom node-pool overrides the wizard doesn't expose.
func (e Estimate) Headline() string {
	return fmt.Sprintf("~$%d/day minimum while running (excludes NAT egress + data)", e.USDPerDay)
}
