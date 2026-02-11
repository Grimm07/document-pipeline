#!/usr/bin/env bash
#
# dev.sh — Single-command startup for the Document Pipeline dev environment.
#
# Starts Docker infrastructure (PostgreSQL, RabbitMQ, ML service), the Ktor
# backend API, the background worker (RabbitMQ consumer), and the Vite
# frontend dev server. Output from each process is prefixed with
# [infra], [api], [worker], or [ui] labels for readability.
#
# Usage:
#   ./scripts/dev.sh              # Normal start. Skips processes already running.
#   ./scripts/dev.sh --restart    # Kill our processes and restart them fresh.
#   ./scripts/dev.sh --force      # Kill ANY process on our ports, even if it's
#                                 # not part of this project, then start fresh.
#   ./scripts/dev.sh --stop       # Stop all running resources and exit.
#   ./scripts/dev.sh --destroy    # Stop all processes and remove Docker containers/volumes.
#
# Ctrl+C cleanly shuts down all processes started by this script.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Parse flags
# ---------------------------------------------------------------------------
# --restart : Kill and restart only if the port is held by our own process.
# --force   : Kill whatever is on the port, regardless of what it is.
# --stop    : Stop all running resources (processes + Docker) and exit.
# --destroy : Stop all processes and remove Docker containers/volumes.
RESTART=false
FORCE=false
STOP=false
DESTROY=false
for arg in "$@"; do
  case "$arg" in
    --restart) RESTART=true ;;
    --force)   FORCE=true ;;
    --stop)    STOP=true ;;
    --destroy) DESTROY=true ;;
    *)
      echo "Unknown flag: $arg" >&2
      echo "Usage: $0 [--restart | --force | --stop | --destroy]" >&2
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# GPU detection — use mock ML service when no GPU available
# ---------------------------------------------------------------------------
COMPOSE_FILES="-f docker/docker-compose.yml"
if command -v nvidia-smi &>/dev/null && nvidia-smi &>/dev/null; then
  echo "[ml]   GPU detected — using real ML service."
  ML_TIMEOUT=180
else
  echo "[ml]   No GPU detected — using mock ML service."
  COMPOSE_FILES="$COMPOSE_FILES -f docker/docker-compose.mock.yml"
  ML_TIMEOUT=30
fi

# ---------------------------------------------------------------------------
# --stop: tear down everything and exit
# ---------------------------------------------------------------------------
if $STOP || $DESTROY; then
  if $DESTROY; then
    echo "Destroying all dev resources..."
  else
    echo "Stopping all dev resources..."
  fi

  # Kill API server (port 8080)
  API_PID=$(lsof -ti :8080 2>/dev/null | head -1 || true)
  if [[ -n "$API_PID" ]]; then
    echo "[api]    Stopping (PID $API_PID)"
    kill "$API_PID" 2>/dev/null || true
  fi

  # Kill worker (no port — match by process pattern)
  WORKER_PID=$(pgrep -f "pipeline.worker" 2>/dev/null | head -1 || true)
  if [[ -n "$WORKER_PID" ]]; then
    echo "[worker] Stopping (PID $WORKER_PID)"
    kill "$WORKER_PID" 2>/dev/null || true
  fi

  # Kill frontend dev server (port 5173)
  UI_PID=$(lsof -ti :5173 2>/dev/null | head -1 || true)
  if [[ -n "$UI_PID" ]]; then
    echo "[ui]     Stopping (PID $UI_PID)"
    kill "$UI_PID" 2>/dev/null || true
  fi

  # Docker: --destroy removes containers + volumes; --stop just pauses them
  if $DESTROY; then
    echo "[infra]  Removing Docker containers and volumes..."
    docker compose $COMPOSE_FILES down -v
    echo "[infra]  Removed."
  else
    echo "[infra]  Stopping Docker services..."
    docker compose $COMPOSE_FILES stop
    echo "[infra]  Stopped."
  fi

  # Kill any lingering Gradle daemons started by this project
  ./gradlew --stop 2>/dev/null || true

  if $DESTROY; then
    echo "All resources destroyed."
  else
    echo "All resources stopped."
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
# check_port — Identify what's listening on a port
# ---------------------------------------------------------------------------
# Arguments:
#   $1 — port number to check (e.g. 8080)
#   $2 — pattern to match in the process command line to decide if it's "ours"
#         (e.g. "pipeline.api" matches the Ktor main class,
#          "vite" matches the Vite dev server)
#
# Sets two "return" variables (global scope):
#   PORT_PID  — PID of the process on the port (empty string if port is free)
#   PORT_OURS — "true" if the process command line contains the pattern,
#               "false" otherwise
#
# How it works:
#   1. `lsof -ti :PORT` lists PIDs listening on the port (-t = terse/PID-only,
#       -i = network filter). Returns non-zero when nothing listens, so
#       `|| true` prevents set -e from killing the script.
#   2. `head -1` grabs the first PID (a port can have multiple: parent/child,
#       IPv4/IPv6 dual-stack listeners).
#   3. `ps -p PID -o args=` gets the full command line of that PID.
#      We glob-match it against the pattern to decide ownership.
check_port() {
  local port=$1 pattern=$2
  PORT_PID=$(lsof -ti :"$port" 2>/dev/null | head -1 || true)
  PORT_OURS=false
  if [[ -n "$PORT_PID" ]]; then
    local cmd
    cmd=$(ps -p "$PORT_PID" -o args= 2>/dev/null || true)
    # Glob match: *pattern* anywhere in the full command string
    if [[ "$cmd" == *"$pattern"* ]]; then
      PORT_OURS=true
    fi
  fi
}

# ---------------------------------------------------------------------------
# handle_port — Decide what to do when a port is occupied
# ---------------------------------------------------------------------------
# Arguments:
#   $1 — port number
#   $2 — label for log messages (e.g. "api", "ui")
#   $3 — pattern for ownership check (passed to check_port)
#
# Returns (via global variable):
#   Sets SKIP_<label>=true if the process should be left alone
#
# Decision matrix:
#   Port free                         → do nothing, proceed to start it
#   Port held by us + no flags        → skip (reuse existing process)
#   Port held by us + --restart       → kill it, then start fresh
#   Port held by us + --force         → kill it, then start fresh
#   Port held by OTHER + no flags     → print the offending command and exit 1
#   Port held by OTHER + --restart    → print the offending command and exit 1
#   Port held by OTHER + --force      → kill it, then start fresh
handle_port() {
  local port=$1 label=$2 pattern=$3

  check_port "$port" "$pattern"

  # Port is free — nothing to do
  if [[ -z "$PORT_PID" ]]; then
    return
  fi

  if $PORT_OURS; then
    # It's our process. Reuse it unless --restart or --force was passed.
    if $RESTART || $FORCE; then
      echo "[$label] Restarting — killing our process on port $port (PID $PORT_PID)."
      kill "$PORT_PID" 2>/dev/null || true
      sleep 1
    else
      echo "[$label] Already running (PID $PORT_PID), skipping. Use --restart to restart."
      # Mark this service as skipped so we don't launch a duplicate
      eval "SKIP_${label^^}=true"
    fi
  else
    # It's NOT our process. Only --force allows killing a foreign process.
    if $FORCE; then
      echo "[$label] Port $port held by foreign process (PID $PORT_PID) — force-killing."
      echo "       $(ps -p "$PORT_PID" -o args= 2>/dev/null)"
      kill "$PORT_PID" 2>/dev/null || true
      sleep 1
    else
      echo "[$label] Port $port in use by another process (PID $PORT_PID)." >&2
      echo "       $(ps -p "$PORT_PID" -o args= 2>/dev/null)" >&2
      echo "       Use --force to kill it, or stop it manually." >&2
      exit 1
    fi
  fi
}

# ---------------------------------------------------------------------------
# Pre-flight port checks
# ---------------------------------------------------------------------------
# Run these BEFORE Docker so conflicts are caught in < 1 second,
# not after waiting 30+ seconds for container health checks.

SKIP_API=false
SKIP_WORKER=false
SKIP_UI=false
SKIP_ML=false

# Backend: Gradle spawns a JVM whose args include the Ktor main class
# "org.example.pipeline.api.ApplicationKt" — we match on "pipeline.api".
handle_port 8080 "api" "pipeline.api"

# Worker: RabbitMQ consumer — doesn't bind a port, so detect by process pattern.
WORKER_PID=$(pgrep -f "pipeline.worker" 2>/dev/null | head -1 || true)
if [[ -n "$WORKER_PID" ]]; then
  if $RESTART || $FORCE; then
    echo "[worker] Restarting — killing worker process (PID $WORKER_PID)."
    kill "$WORKER_PID" 2>/dev/null || true
    sleep 1
  else
    echo "[worker] Already running (PID $WORKER_PID), skipping. Use --restart to restart."
    SKIP_WORKER=true
  fi
fi

# Frontend: Vite's Node process always has "vite" in its command line.
handle_port 5173 "ui" "vite"

# ML service: uvicorn running the FastAPI classification service.
handle_port 8000 "ml" "uvicorn"

# If all services are already running and we're not restarting, nothing to do.
if $SKIP_API && $SKIP_WORKER && $SKIP_UI && $SKIP_ML; then
  echo "All services already running."
  exit 0
fi

# ---------------------------------------------------------------------------
# Start Docker infrastructure
# ---------------------------------------------------------------------------
# docker compose up -d is idempotent — already-running containers are untouched.
# --wait blocks until all healthchecks pass (Postgres pg_isready, RabbitMQ ping).
echo "[infra] Starting Docker services..."
docker compose $COMPOSE_FILES up -d --wait
echo "[infra] Ready."

# ---------------------------------------------------------------------------
# Wait for ML service health (non-blocking)
# ---------------------------------------------------------------------------
# The ML service loads ~4GB of model weights on first start. We poll its
# /health endpoint for up to 180s. If it doesn't come up in time, continue
# anyway — the API and worker will gracefully fail classification until ML
# is ready.
if ! $SKIP_ML; then
  echo "[ml]   Waiting for ML service on port 8000 (model loading may take a few minutes)..."
  ML_ELAPSED=0
  while ! curl -sf http://localhost:8000/health 2>/dev/null | grep -q '"models_loaded":true'; do
    if (( ML_ELAPSED >= ML_TIMEOUT )); then
      echo "[ml]   ML service not ready after ${ML_TIMEOUT}s — continuing without it."
      break
    fi
    sleep 5
    (( ML_ELAPSED += 5 ))
  done
  if (( ML_ELAPSED < ML_TIMEOUT )); then
    echo "[ml]   Ready."
  fi
fi

# ---------------------------------------------------------------------------
# Trap for clean shutdown
# ---------------------------------------------------------------------------
# `kill 0` sends SIGTERM to every process in this script's process group,
# which includes the backgrounded Gradle and Vite pipelines. This ensures
# Ctrl+C cleans up everything we started in this session.
trap 'echo; echo "Shutting down..."; kill 0' EXIT

# ---------------------------------------------------------------------------
# Start backend API (port 8080)
# ---------------------------------------------------------------------------
if ! $SKIP_API; then
  # Gradle runs Ktor with Netty. The pipeline through `sed` prefixes each
  # line with [api] so you can tell backend output apart from frontend.
  # `$!` captures the PID of the last command in the background pipeline (sed),
  # which exits when Gradle exits (pipe closes), so it's a reliable proxy.
  ./gradlew :app-api:run 2>&1 | sed 's/^/[api] /' &
  API_PID=$!

  # Poll the API endpoint until it responds. Gradle compiles before Ktor
  # starts, so this can take 30-60s on a cold daemon (120s timeout).
  echo "[api]  Waiting for backend on port 8080..."
  TIMEOUT=120
  ELAPSED=0
  while ! curl -sf http://localhost:8080/api/documents > /dev/null 2>&1; do
    # Check if the Gradle/sed pipeline is still alive
    if ! kill -0 "$API_PID" 2>/dev/null; then
      echo "[api]  Backend process died." >&2
      exit 1
    fi
    if (( ELAPSED >= TIMEOUT )); then
      echo "[api]  Timed out after ${TIMEOUT}s." >&2
      exit 1
    fi
    sleep 2
    (( ELAPSED += 2 ))
  done
  echo "[api]  Ready."
fi

# ---------------------------------------------------------------------------
# Start background worker (RabbitMQ consumer → ML service)
# ---------------------------------------------------------------------------
if ! $SKIP_WORKER; then
  ./gradlew :app-worker:run 2>&1 | sed 's/^/[worker] /' &
  echo "[worker] Starting background worker..."
fi

# ---------------------------------------------------------------------------
# Start frontend dev server (port 5173)
# ---------------------------------------------------------------------------
# Only started after the backend is confirmed ready (or was already running).
# Vite proxies /api/* requests to localhost:8080, so the backend must be up.
if ! $SKIP_UI; then
  (cd frontend && npm run dev) 2>&1 | sed 's/^/[ui]  /' &
fi

# ---------------------------------------------------------------------------
# Wait for all background processes to exit (i.e., block until Ctrl+C)
# ---------------------------------------------------------------------------
wait
