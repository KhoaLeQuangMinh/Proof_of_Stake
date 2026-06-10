sed -i '' -e '239,247c\
        if (msg.getType() == NetworkMessage.Type.PROPOSAL) {\
            // Allow multiple proposers. Equivocation is when the SAME sender proposes differently.\
            // We just let ConsensusEngine Phase 2 handle multiple proposals.\
        }\
' src/main/java/network/NetworkEngine.java
