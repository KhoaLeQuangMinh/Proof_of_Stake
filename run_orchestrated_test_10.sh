#!/bin/bash
rm -rf nodes/
pkill -f "app.Main"
pkill -f "network.NodeOrchestrator"

echo "Start Mempool..."
python3 test/mock_mempool.py > mempool.log 2>&1 &
MEMPOOL_PID=$!

echo "Starting Node Orchestrator..."
java -cp target/blockchain.jar network.NodeOrchestrator > orchestrator.log 2>&1 &
ORCH_PID=$!
sleep 3

echo "Booting 10 nodes via the Node Orchestrator API (Nodes 0-4 are Validators, Nodes 5-9 are pure Users)..."
for i in {0..9}
do
    curl -s -X POST http://localhost:9000/api/orchestrator/spawn_node
    echo ""
done

echo "Nodes started, waiting for 20 seconds for genesis blocks..."
sleep 20

echo "Injecting 100 cryptographic transactions (including DEPOSIT, STAKE, DONATE, UNSTAKE, WITHDRAW)..."
java -cp "target/blockchain.jar:lib/*:." tools.TxGenerator

echo "Waiting for 120 seconds for all 100 transactions to be validated and committed..."
sleep 120

echo "Checking node_0_orchestrated.log for successful commits..."
grep "Block applied" node_0_orchestrated.log | tail -n 10

echo "Checking node_0_orchestrated.log for validation of transactions..."
grep "TXN_VALIDATED" node_0_orchestrated.log | wc -l | awk '{print "Transactions validated: "$1"/100"}'

# Kill all background processes
pkill -f "app.Main"
kill -9 $MEMPOOL_PID $ORCH_PID
echo "Test complete!"
