#!/bin/bash
rm -rf nodes/

# Start Mempool
python3 test/mock_mempool.py > mempool.log 2>&1 &
MEMPOOL_PID=$!

# Start 5 nodes
java -cp "target/blockchain.jar:lib/*:." app.Main 0 5 > node0.log 2>&1 &
NODE0_PID=$!
java -cp "target/blockchain.jar:lib/*:." app.Main 1 5 > node1.log 2>&1 &
NODE1_PID=$!
java -cp "target/blockchain.jar:lib/*:." app.Main 2 5 > node2.log 2>&1 &
NODE2_PID=$!
java -cp "target/blockchain.jar:lib/*:." app.Main 3 5 > node3.log 2>&1 &
NODE3_PID=$!
java -cp "target/blockchain.jar:lib/*:." app.Main 4 5 > node4.log 2>&1 &
NODE4_PID=$!

echo "Nodes started, waiting for 10 seconds for consensus to establish empty blocks..."
sleep 30

echo "Injecting a valid DONATE transaction..."
curl -s -X POST http://localhost:8080/inject -H "Content-Type: application/json" -d '{
  "txId": "5c6c2d028b15b61b52fc17fe2946ca854793a9f3f0bddd0a52c16a40c535a8e2",
  "type": "DONATE",
  "senderPubKey": "IVk/jVu2VOyZqdmr6P6MfmHJcHrclMrYilREXYHCQeo=",
  "receiverPubKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
  "amount": 50.0,
  "firstValid": 0,
  "lastValid": 999999,
  "timestamp": 1735689600000,
  "ed25519Signature": "MEQl/imznIq2qWWnPeWCpSPMsrf7tIU9jJ1610ZGik8Dc4zdQsLKf9l/+FyJF7wwQ2d9MMtWY3Kr3ie87uAyCA=="
}'
echo ""

echo "Waiting for 10 seconds for transaction to be mined..."
sleep 30

echo "Checking node0.log for TXN_VALIDATED..."
grep -A 2 -B 2 "TXN_VALIDATED" node0.log || echo "NOT FOUND"
echo "Checking node0.log for applied txs..."
grep "Block applied" node0.log | tail -n 5

# Kill all
kill -9 $MEMPOOL_PID $NODE0_PID $NODE1_PID $NODE2_PID $NODE3_PID $NODE4_PID
