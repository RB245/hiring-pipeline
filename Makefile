.PHONY: up down test test-frontend frontend

up:
	docker compose up --build

down:
	docker compose down

# Both halves, because "make test" that runs half the tests is worse than no target.
test:
	cd backend && ./gradlew test
	cd frontend && npm test

test-frontend:
	cd frontend && npm test

frontend:
	cd frontend && npm run dev
