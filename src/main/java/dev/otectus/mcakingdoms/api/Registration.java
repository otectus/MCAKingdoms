package dev.otectus.mcakingdoms.api;

@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
