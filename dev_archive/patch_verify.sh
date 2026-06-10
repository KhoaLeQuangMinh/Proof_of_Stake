sed -i '' -e 's/continue; \/\/ Skip malformed entries/System.out.println("[verifyCertificate] malformed entry"); continue;/g' src/main/java/state/StateEngine.java
sed -i '' -e 's/\} catch (Exception e) {/\} catch (Exception e) { System.out.println("[verifyCertificate] sig check exception: " + e.getMessage()); /g' src/main/java/state/StateEngine.java
sed -i '' -e 's/if (weight > 0 && sigValid) {/System.out.println("[verifyCertificate] w=" + weight + " sig=" + sigValid); if (weight > 0 \&\& sigValid) {/g' src/main/java/state/StateEngine.java
