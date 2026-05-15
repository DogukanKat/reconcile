.PHONY: help up down build test clean register-connector connector-status \
	registry-subjects registry-compat

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

up: ## Start local infrastructure (Postgres + Kafka + Debezium + Schema Registry)
	docker-compose up -d

down: ## Stop local infrastructure
	docker-compose down

build: ## Build all modules
	./gradlew build

test: ## Run all tests
	./gradlew test

clean: ## Clean build artifacts
	./gradlew clean

register-connector: ## Register the Debezium outbox connector (idempotent)
	@curl -sf -X POST -H "Content-Type: application/json" \
		--data @infra/debezium/outbox-connector.json \
		http://localhost:8083/connectors > /dev/null && echo "connector registered" \
		|| jq '.config' infra/debezium/outbox-connector.json \
			| curl -s -X PUT -H "Content-Type: application/json" \
				--data @- \
				http://localhost:8083/connectors/payment-outbox/config \
			| jq -r '.connector.state // "updated"'

connector-status: ## Show Debezium connector status
	@curl -s http://localhost:8083/connectors/payment-outbox/status | jq

registry-subjects: ## List Schema Registry subjects
	@curl -s http://localhost:8085/subjects | jq

registry-compat: ## Show compatibility level for a subject (SUBJECT=name)
	@curl -s http://localhost:8085/config/$(SUBJECT) | jq

.DEFAULT_GOAL := help
