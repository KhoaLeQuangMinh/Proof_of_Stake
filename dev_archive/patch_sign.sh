sed -i '' -e 's/byte\[\] hashToSign;/byte\[\] hashToSign; System.out.println("[Phase 6] Signing finalWinner=" + finalWinner);/g' src/main/java/consensus/ConsensusEngine.java
