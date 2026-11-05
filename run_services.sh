#!/usr/bin/env bash
# ==============================================================================
# Big Data Search Engine - Service Orchestrator (Stage 2: SOA)
# Runs ingestion-service (7001), indexing-service (7002), search-service (7003)
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LOGS_DIR="$SCRIPT_DIR/logs"
PID_FILE="$LOGS_DIR/services.pid"

mkdir -p "$LOGS_DIR"
mkdir -p "$SCRIPT_DIR/datalake"
mkdir -p "$SCRIPT_DIR/datamarts"

# Resolve Java & Maven if in standard macOS locations
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    elif [ -d "/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home"
    fi
fi

if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v mvn &> /dev/null; then
    if [ -x "$HOME/.sdkman/candidates/maven/current/bin/mvn" ]; then
        export PATH="$HOME/.sdkman/candidates/maven/current/bin:$PATH"
    fi
fi

# ------------------------------------------------------------------------------
# Functions
# ------------------------------------------------------------------------------

build_jars_if_needed() {
    local need_build=0
    for s in ingestion-service indexing-service search-service; do
        if [ ! -f "$SCRIPT_DIR/$s/target/$s-1.0.0.jar" ]; then
            need_build=1
            break
        fi
    done

    if [ "$need_build" -eq 1 ]; then
        echo "Building service JARs with Maven..."
        mvn -f ingestion-service/pom.xml package -DskipTests
        mvn -f indexing-service/pom.xml package -DskipTests
        mvn -f search-service/pom.xml package -DskipTests
        echo "Build completed."
    fi
}

start_services() {
    if [ -f "$PID_FILE" ]; then
        echo "Services may already be running (PID file exists: $PID_FILE)."
        echo "Run './run_services.sh stop' first if you want to restart."
        exit 1
    fi

    build_jars_if_needed

    echo "Starting services..."

    PORT=7001 DATALAKE_PATH="$SCRIPT_DIR/datalake" \
        java -jar "$SCRIPT_DIR/ingestion-service/target/ingestion-service-1.0.0.jar" \
        > "$LOGS_DIR/ingestion.log" 2>&1 &
    INGESTION_PID=$!

    PORT=7002 DATALAKE_PATH="$SCRIPT_DIR/datalake" DATAMART_PATH="$SCRIPT_DIR/datamarts/datamart.db" \
        java -jar "$SCRIPT_DIR/indexing-service/target/indexing-service-1.0.0.jar" \
        > "$LOGS_DIR/indexing.log" 2>&1 &
    INDEXING_PID=$!

    PORT=7003 DATAMART_PATH="$SCRIPT_DIR/datamarts/datamart.db" \
        java -jar "$SCRIPT_DIR/search-service/target/search-service-1.0.0.jar" \
        > "$LOGS_DIR/search.log" 2>&1 &
    SEARCH_PID=$!

    echo "$INGESTION_PID $INDEXING_PID $SEARCH_PID" > "$PID_FILE"

    echo "Waiting for services to become healthy..."
    local attempts=0
    local max_attempts=20
    until (curl -s http://localhost:7001/status > /dev/null && \
           curl -s http://localhost:7002/status > /dev/null && \
           curl -s http://localhost:7003/status > /dev/null) || [ $attempts -eq $max_attempts ]; do
        sleep 0.5
        attempts=$((attempts + 1))
    done

    if [ $attempts -eq $max_attempts ]; then
        echo "Warning: Services took longer than expected to report status. Check logs in $LOGS_DIR."
    else
        echo "=========================================================="
        echo "All 3 services are running and healthy!"
        echo "----------------------------------------------------------"
        echo "  Ingestion Service: http://localhost:7001 (PID $INGESTION_PID)"
        echo "  Indexing Service:  http://localhost:7002 (PID $INDEXING_PID)"
        echo "  Search Service:    http://localhost:7003 (PID $SEARCH_PID)"
        echo "----------------------------------------------------------"
        echo "  Logs: $LOGS_DIR/*.log"
        echo "  To stop: ./run_services.sh stop"
        echo "=========================================================="
    fi
}

stop_services() {
    if [ ! -f "$PID_FILE" ]; then
        echo "No PID file found at $PID_FILE. Trying to kill any running instances on ports 7001, 7002, 7003..."
        for port in 7001 7002 7003; do
            pid=$(lsof -ti :$port 2>/dev/null || true)
            if [ -n "$pid" ]; then
                kill $pid 2>/dev/null || true
            fi
        done
        echo "Done."
        return
    fi

    read -r p1 p2 p3 < "$PID_FILE"
    echo "Stopping services (PIDs: $p1 $p2 $p3)..."
    kill "$p1" "$p2" "$p3" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "All services stopped."
}

check_status() {
    echo "Checking service status..."
    echo -n "  Ingestion Service (7001): "
    curl -s http://localhost:7001/status || echo "DOWN"
    echo ""
    echo -n "  Indexing Service  (7002): "
    curl -s http://localhost:7002/status || echo "DOWN"
    echo ""
    echo -n "  Search Service    (7003): "
    curl -s http://localhost:7003/status || echo "DOWN"
    echo ""
}

# ------------------------------------------------------------------------------
# Entry Point
# ------------------------------------------------------------------------------

case "$1" in
    start)
        start_services
        ;;
    stop)
        stop_services
        ;;
    status)
        check_status
        ;;
    restart)
        stop_services
        sleep 1
        start_services
        ;;
    *)
        # Default: start in foreground, trap SIGINT
        start_services
        trap stop_services EXIT INT TERM
        echo "Press Ctrl+C to stop all services..."
        tail -f "$LOGS_DIR/ingestion.log" "$LOGS_DIR/indexing.log" "$LOGS_DIR/search.log"
        ;;
esac

