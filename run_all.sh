#!/bin/bash
pkill -f "java -jar"
sleep 2
rm -f nodes/*.db
rm -f nodes/*.log

for i in {0..4}; do
    java -jar nodes/node_$i/app.jar $i > nodes/node_$i.log 2>&1 &
done
echo "Started 5 nodes."
