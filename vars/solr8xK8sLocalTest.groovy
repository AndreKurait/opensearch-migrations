// Placeholder: Solr 8.x K8s local test will be enabled once the SolrCloud
// backup integration is merged. See: SolrBackfillIntegTesting branch.
def call(Map config = [:]) {
    node(label: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host') {
        echo 'Solr 8.x K8s local test - placeholder (no-op until SolrCloud backup support is merged)'
    }
}
