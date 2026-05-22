package wizard

// stepInfo returns the multi-line help text shown when the user
// presses [i] on a wizard step. Each line is prefixed with "• " or
// blank; the page renders them as a small indented block.
//
// Keep these short — link out to docs only when there's a real upstream
// page to point at. The ABSOLUTE pricing numbers come from
// internal/cost/cost.go for consistency.
func stepInfo(idx int) []string {
	switch idx {
	case stepIdentity:
		return []string{
			"Resolved via sts:GetCallerIdentity at launch.",
			"This principal is automatically granted",
			"AmazonEKSClusterAdminPolicy on the cluster.",
			"To use a different IAM user/role, press [a] on the welcome",
			"screen to switch your AWS_PROFILE before continuing.",
		}
	case stepRegion:
		return []string{
			"AWS region where the EKS cluster, ECR registry, and helm",
			"release will be created.",
			"Pre-filled from `aws configure get region`. Pricing varies",
			"slightly across regions; major regions track each other.",
		}
	case stepStage:
		return []string{
			"Free-text label that names this MA install. Becomes part of",
			"every CFN export, IAM role, and EKS cluster name (e.g.",
			"migration-eks-cluster-${stage}-${region}).",
			"Use 'dev' / 'staging' / 'prod' to host multiple installs in",
			"the same account/region without conflict.",
		}
	case stepScope:
		return []string{
			"Create VPC: bootstrap creates a brand-new VPC with 2 public",
			"+ 2 private subnets across AZs. ~$2.16/day NAT GW cost.",
			"Import VPC: reuse an existing VPC; you pick subnets next.",
			"  Required: 2+ subnets in different AZs with NAT or IGW.",
			"Skip CFN: assumes the EKS cluster already exists; only the",
			"  helm install runs. Use to retry a failed helm step.",
			"All scopes provision: 1 t3.large for the migration-console",
			"and 2 m5.large for backfill/replay/argo — ~$10/day baseline.",
		}
	case stepVPC:
		return []string{
			"VPC the EKS cluster + ENIs are placed in. We list every VPC",
			"in the chosen region; default VPC is marked.",
			"If detection fails (no AWS creds, AccessDenied), [m] flips",
			"to manual entry — paste the vpc-XXXX ID directly.",
		}
	case stepSubnets:
		return []string{
			"At least 2 subnets in DIFFERENT availability zones.",
			"NAT/IGW: public-access subnets — pick these for the default",
			"  setup with internet egress.",
			"ISOLATED: no NAT — image pulls require VPC endpoints. The",
			"  next step auto-detects existing endpoints and proposes",
			"  only the missing ones (s3, ecr, ecrDocker).",
		}
	case stepEndpoints:
		return []string{
			"VPC interface endpoints make ECR + STS reachable from",
			"isolated subnets. Without these, image pulls fail and the",
			"deploy stalls 8 minutes in.",
			"S3 (gateway endpoint): FREE. ecr/ecrDocker: $0.48/day each.",
			"Already-installed endpoints are skipped; this step only",
			"creates what's missing.",
		}
	case stepSource:
		return []string{
			"Latest published: download artifacts from the latest GitHub",
			"  release tag. Most users want this.",
			"Specific version: pin to a known-good MA version (e.g. 3.2.1).",
			"Build from source: gradle-build images locally and push to",
			"  ECR. Requires a repo checkout; takes 10-30 min.",
		}
	case stepTLS:
		return []string{
			"none: traffic is HTTP-only inside the cluster. Dev only.",
			"self-signed: chart generates a CA and issues certs (default).",
			"import existing PCA: BYO AWS Private CA — paste its ARN.",
			"create new PCA: bootstrap creates a fresh AWS Private CA",
			"  ($400/month flat = ~$13.33/day). 7-day delete grace period",
			"  means accidental creates LINGER. Pick deliberately.",
		}
	case stepEKSAccess:
		return []string{
			"Your current caller (resolved at launch) is granted cluster",
			"admin automatically — you don't need to list it here.",
			"Use this field to grant ADDITIONAL principals access:",
			"  arn:aws:iam::123:role/Teammate,arn:aws:iam::123:role/CI",
			"Each entry maps to an EKS access-entry +",
			"AmazonEKSClusterAdminPolicy association.",
		}
	case stepAdvanced:
		return []string{
			"Reserved for namespace overrides, custom image tags, and",
			"node-pool tuning. v1 ships with sane defaults; press enter",
			"to keep them. Pass --helm-values <file> on the equivalent",
			"command line for full chart-value overrides.",
		}
	}
	return []string{"(no info available for this step)"}
}
