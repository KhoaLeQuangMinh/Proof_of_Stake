#!/bin/bash

# Clear old dirty databases to ensure all nodes start at exactly Round 1 / Genesis
echo "🧹 Cleaning up old databases..."
rm -rf nodes/
mkdir -p nodes/

echo "🚀 Launching all 5 nodes simultaneously..."

# Launch all nodes in the background
java -jar target/blockchain.jar 0 5 http://localhost:8080 amqp://localhost:5672 > nodes/node_0.log 2>&1 &
java -jar target/blockchain.jar 1 5 http://localhost:8080 amqp://localhost:5672 > nodes/node_1.log 2>&1 &
java -jar target/blockchain.jar 2 5 http://localhost:8080 amqp://localhost:5672 > nodes/node_2.log 2>&1 &
java -jar target/blockchain.jar 3 5 http://localhost:8080 amqp://localhost:5672 > nodes/node_3.log 2>&1 &
java -jar target/blockchain.jar 4 5 http://localhost:8080 amqp://localhost:5672 > nodes/node_4.log 2>&1 &

echo "✅ All nodes launched in the background!"
echo "You can monitor the logs by running: tail -f nodes/node_*.log"
echo "To stop all nodes, press Ctrl+C, then run: pkill -f 'target/blockchain.jar'"

# Wait for all background processes to finish
wait
