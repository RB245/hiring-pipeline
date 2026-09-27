.PHONY: up down test frontend

up:
	docker compose up --build

down:
	docker compose down

test:
	cd backend && ./gradlew test

frontend:
	cd frontend && npm run dev
