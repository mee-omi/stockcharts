# StockCharts — build & deploy automation
IMAGE_PREFIX ?= stockcharts
TAG          ?= latest

.PHONY: help build up down logs ps test \
        docker-backend docker-data docker-frontend \
        kind-load k8s-deploy k8s-deploy-prod k8s-delete k8s-status clean

help:
	@echo "StockCharts targets:"
	@echo "  make up               - build & run the full stack (mock data + paper trading)"
	@echo "  make up-zerodha       - run with Zerodha market data (requires ENCTOKEN)"
	@echo "  make up-zerodha-trade - run with Zerodha market data + Kite trading"
	@echo "  make up-angelone      - run with Angel One market data + SmartAPI trading"
	@echo "  make down             - stop the local stack"
	@echo "  make logs             - tail local stack logs"
	@echo "  make build            - build all three Docker images ($(IMAGE_PREFIX)/*:$(TAG))"
	@echo "  make test             - run backend unit tests + frontend build"
	@echo "  make kind-load        - load images into a local kind cluster"
	@echo "  make k8s-deploy       - apply the dev overlay (mock data) to the cluster"
	@echo "  make k8s-deploy-prod  - apply the prod overlay (real Zerodha enctoken)"
	@echo "  make k8s-delete       - remove the dev deployment"
	@echo "  make k8s-status       - show pods/services/ingress in the namespace"

# ---- local dev (docker compose) ----
up:
	docker compose up --build -d
	@echo "Frontend:  http://localhost:3000"
	@echo "Backend:   http://localhost:8080/swagger-ui.html"
	@echo "Data svc:  http://localhost:8000/health"

# Zerodha market data only (paper trading). Requires ENCTOKEN env var.
up-zerodha:
	MARKET_DATA_PROVIDER=zerodha docker compose up --build -d
	@echo "Live Zerodha market data active. Trading uses paper (mock) broker."

# Zerodha market data + Kite Connect live trading.
# Requires: ENCTOKEN, ZERODHA_API_KEY, ZERODHA_ACCESS_TOKEN
up-zerodha-trade:
	MARKET_DATA_PROVIDER=zerodha TRADING_BROKER=zerodha docker compose up --build -d
	@echo "Live Zerodha market data + Kite Connect trading active."

# Angel One SmartAPI market data + trading.
# Requires: ANGELONE_API_KEY, ANGELONE_CLIENT_ID, ANGELONE_TOTP_SECRET
up-angelone:
	MARKET_DATA_PROVIDER=angelone TRADING_BROKER=angelone docker compose up --build -d
	@echo "Angel One SmartAPI market data + trading active."

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

# ---- images ----
docker-data:
	docker build -t $(IMAGE_PREFIX)/data-service:$(TAG) ./data-service

docker-backend:
	docker build -t $(IMAGE_PREFIX)/backend:$(TAG) ./backend

docker-frontend:
	docker build -t $(IMAGE_PREFIX)/frontend:$(TAG) ./frontend

build: docker-data docker-backend docker-frontend

# ---- tests ----
test:
	cd backend && ./mvnw -q test
	cd frontend && npm ci && npm run build

# ---- kubernetes ----
kind-load: build
	kind load docker-image $(IMAGE_PREFIX)/data-service:$(TAG)
	kind load docker-image $(IMAGE_PREFIX)/backend:$(TAG)
	kind load docker-image $(IMAGE_PREFIX)/frontend:$(TAG)

k8s-deploy: build
	kubectl apply -k k8s/overlays/dev
	@echo "Add '127.0.0.1 stockcharts.local' to /etc/hosts, then open http://stockcharts.local"

k8s-deploy-prod: build
	kubectl apply -k k8s/overlays/prod

k8s-delete:
	kubectl delete -k k8s/overlays/dev || true

k8s-status:
	kubectl -n stockcharts get pods,svc,ingress

clean:
	docker compose down -v || true
	cd backend && ./mvnw -q clean || true
	rm -rf frontend/dist frontend/node_modules
