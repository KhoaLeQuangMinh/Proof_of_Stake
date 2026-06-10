sed -i '' -e 's/if (!created) {/if (!created \&\& !parentDir.exists()) {/g' src/main/java/state/DatabaseManager.java
