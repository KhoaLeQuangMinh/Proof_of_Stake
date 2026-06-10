#!/bin/bash
# Move the closing brace to the end of the file
sed -i '' '/^}$/d' src/main/java/buffer/SharedBuffer.java
echo "}" >> src/main/java/buffer/SharedBuffer.java
