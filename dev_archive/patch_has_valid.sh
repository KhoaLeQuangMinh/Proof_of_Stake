#!/bin/bash

# Insert hasValidCertificateInterrupt method
sed -i '' '/private void executeRound(long round)/i \
    private boolean hasValidCertificateInterrupt(long round) {\
        certificateQueue.removeIf(cert -> cert.getRound() < round);\
        for (model.BlockCertificate cert : certificateQueue) {\
            if (cert.getRound() == round) {\
                return true;\
            }\
        }\
        return false;\
    }\
' src/main/java/consensus/ConsensusEngine.java

# Replace !certificateQueue.isEmpty() with hasValidCertificateInterrupt(round)
sed -i '' 's/!certificateQueue.isEmpty()/hasValidCertificateInterrupt(round)/g' src/main/java/consensus/ConsensusEngine.java
