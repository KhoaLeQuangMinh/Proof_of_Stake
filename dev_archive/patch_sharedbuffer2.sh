#!/bin/bash
perl -0777 -pi -e 's/            capturedBatch = null;\n            capturedBatchId = -1L;\n            \}\n        \}\n    \}/            capturedBatch = null;\n            capturedBatchId = -1L;\n        }\n    }/g' src/main/java/buffer/SharedBuffer.java
