.PHONY: build test test-update lint fmt cover cover-xml run testdata-budget tidy clean

BIN_DIR ?= build/bin
COV_DIR ?= build/coverage
MODULE  := github.com/opensearch-project/opensearch-migrations/tui

build:
	@mkdir -p $(BIN_DIR)
	go build -o $(BIN_DIR)/migration-assistant ./cmd/tui

test:
	go test -race -timeout 2m ./...

# Regenerate golden files for visual snapshots (PLAN §11.3).
test-update:
	go test ./... -update

lint:
	@command -v golangci-lint >/dev/null 2>&1 || { echo "golangci-lint not installed (https://golangci-lint.run)"; exit 1; }
	golangci-lint run ./...

fmt:
	@command -v gofumpt >/dev/null 2>&1 && gofumpt -l -w . || gofmt -l -w .
	@command -v goimports >/dev/null 2>&1 && goimports -local $(MODULE) -w . || true

# Coverage: native Go profile (for `go tool cover -html`) AND Cobertura XML (Codecov).
cover:
	@mkdir -p $(COV_DIR)
	go test -race -covermode=atomic -coverpkg=./internal/... \
	    -coverprofile=$(COV_DIR)/coverage.out ./...
	go tool cover -func=$(COV_DIR)/coverage.out | tail -1

cover-xml: cover
	@command -v gocover-cobertura >/dev/null 2>&1 || go install github.com/boumenot/gocover-cobertura@latest
	gocover-cobertura < $(COV_DIR)/coverage.out > $(COV_DIR)/coverage.xml
	@echo "Cobertura XML: $(COV_DIR)/coverage.xml"

# PLAN §11.3 — fail if total testdata exceeds 1MB.
testdata-budget:
	@total=$$(find . -type d -name testdata -exec du -sk {} + 2>/dev/null | awk '{s+=$$1} END{print s+0}'); \
	 if [ "$$total" -gt 1024 ]; then echo "testdata budget: $${total}KB > 1024KB"; exit 1; fi
	@echo "testdata budget OK"

tidy:
	go mod tidy

run:
	go run ./cmd/tui

clean:
	rm -rf $(BIN_DIR) $(COV_DIR)
