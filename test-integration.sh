#!/usr/bin/env bash

set -e

echo "==> Starting integration tests..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Start the server in background
echo -e "${YELLOW}==> Starting JVM test server...${NC}"
clj -M:test -m kabel.integration-server &
SERVER_PID=$!

# Function to cleanup on exit
cleanup() {
    echo -e "${YELLOW}==> Cleaning up...${NC}"
    if [ ! -z "$SERVER_PID" ]; then
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
}

# Register cleanup on exit
trap cleanup EXIT INT TERM

# Wait for server to start (check if port is open)
echo -e "${YELLOW}==> Waiting for server to start...${NC}"
MAX_WAIT=10
WAITED=0
while ! nc -z localhost 47295 2>/dev/null; do
    if [ $WAITED -ge $MAX_WAIT ]; then
        echo -e "${RED}==> Server failed to start within ${MAX_WAIT} seconds${NC}"
        exit 1
    fi
    sleep 1
    WAITED=$((WAITED + 1))
done

echo -e "${GREEN}==> Server started successfully${NC}"

# Compile the integration test
echo -e "${YELLOW}==> Compiling integration tests...${NC}"
npx shadow-cljs compile integration

# Run the integration test
echo -e "${YELLOW}==> Running integration tests...${NC}"
if node target/integration-test.js; then
    echo -e "${GREEN}==> Integration tests passed!${NC}"
    exit 0
else
    echo -e "${RED}==> Integration tests failed!${NC}"
    exit 1
fi
