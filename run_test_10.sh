#!/bin/bash
rm -rf nodes/

echo "Compiling..."
mvn clean package -DskipTests

# Start Mempool
python3 test/mock_mempool.py > mempool.log 2>&1 &
MEMPOOL_PID=$!

echo "Booting 10 nodes (Nodes 0-4 are Validators, Nodes 5-9 are pure Users)..."
PIDS=""
for i in {0..9}
do
    java -cp "target/blockchain.jar:lib/*:." app.Main $i 10 > node$i.log 2>&1 &
    PIDS="$PIDS $!"
done

echo "Nodes started, waiting for 20 seconds for genesis blocks..."
sleep 20

echo "Injecting 100 cryptographic transactions (including DEPOSIT, STAKE, DONATE, UNSTAKE, WITHDRAW)..."
java -cp "target/blockchain.jar:lib/*:." tools.TxGenerator

echo "Waiting for 60 seconds for transactions to be validated and committed..."
sleep 120

echo "Checking node0.log for successful commits..."
grep "Block applied" node0.log | tail -n 10

echo "Checking node0.log for validation of transactions..."
grep "TXN_VALIDATED" node0.log | wc -l | awk '{print "Transactions validated: "$1"/100"}'

# Kill all background processes
kill -9 $MEMPOOL_PID $PIDS
echo "Test complete!"
