.PHONY: up down test test-frontend frontend docs

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

# Regenerates the two artefacts that cannot be written by hand. The GIF needs the stack
# running, because it records the real application rather than a mockup.
docs:
	cd docs/tooling && npm install && npm run pdf && npm run record
