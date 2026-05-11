.PHONY: help up down build test clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## Start local infrastructure (Postgres)
	docker-compose up -d

down: ## Stop local infrastructure
	docker-compose down

build: ## Build all modules
	./gradlew build

test: ## Run all tests
	./gradlew test

clean: ## Clean build artifacts
	./gradlew clean

.DEFAULT_GOAL := help
