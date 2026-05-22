package cost

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestEstimateCreateVPCWithEndpoints(t *testing.T) {
	t.Parallel()
	got := EstimateDailyUSD(Inputs{
		Scope:        "create-vpc",
		VPCEndpoints: []string{"s3", "ecr", "ecrDocker"},
		SubnetCount:  2,
		TLSMode:      "self-signed",
	})
	// Sanity range: ~$13/day baseline + $2.16 NAT + 2×$0.48 endpoints ≈ $16/day.
	require.Greater(t, got.USDPerDay, 11)
	require.Less(t, got.USDPerDay, 22)

	// Composition checks against the real per-instance lines.
	var hasNAT, hasECR, hasS3, hasPCA, hasT3, hasM5 bool
	for _, l := range got.Lines {
		if strings.Contains(l.Component, "NAT") {
			hasNAT = true
		}
		if strings.Contains(l.Component, "ecr") || strings.Contains(l.Component, "ecrDocker") {
			hasECR = true
		}
		if strings.Contains(l.Component, "S3 (snapshots") {
			hasS3 = true
		}
		if strings.Contains(l.Component, "Private CA") {
			hasPCA = true
		}
		if strings.Contains(l.Component, "t3.large") {
			hasT3 = true
		}
		if strings.Contains(l.Component, "m5.large") {
			hasM5 = true
		}
	}
	require.True(t, hasNAT, "create-vpc should include NAT Gateway line")
	require.True(t, hasECR, "ecr endpoint should be listed")
	require.True(t, hasS3, "snapshots line always shown")
	require.True(t, hasT3, "migration-console t3.large line should appear")
	require.True(t, hasM5, "services m5.large × 2 line should appear")
	require.False(t, hasPCA, "self-signed should not include PCA")
}

func TestEstimateImportVPCNoNAT(t *testing.T) {
	t.Parallel()
	got := EstimateDailyUSD(Inputs{
		Scope: "import-vpc",
	})
	for _, l := range got.Lines {
		require.NotContains(t, l.Component, "NAT", "import-vpc should NOT add NAT cost")
	}
}

func TestEstimatePCACreateAddsBigCost(t *testing.T) {
	t.Parallel()
	without := EstimateDailyUSD(Inputs{Scope: "import-vpc", TLSMode: "self-signed"})
	with := EstimateDailyUSD(Inputs{Scope: "import-vpc", TLSMode: "pca-create"})
	require.Greater(t, with.USDPerDay, without.USDPerDay+10,
		"pca-create should add at least $10/day (real: $13.33)")
}

func TestHeadlineFormat(t *testing.T) {
	t.Parallel()
	e := Estimate{USDPerDay: 12}
	require.Contains(t, e.Headline(), "~$12/day minimum")
	require.Contains(t, e.Headline(), "excludes NAT egress")
}

func TestS3GatewayEndpointFree(t *testing.T) {
	t.Parallel()
	withS3Only := EstimateDailyUSD(Inputs{Scope: "import-vpc", VPCEndpoints: []string{"s3"}})
	noEndpoints := EstimateDailyUSD(Inputs{Scope: "import-vpc"})
	require.Equal(t, withS3Only.USDPerDay, noEndpoints.USDPerDay,
		"s3 gateway endpoint is free \u2014 should not change the total")
}
