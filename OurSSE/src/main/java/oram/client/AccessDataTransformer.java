package oram.client;

public interface AccessDataTransformer {
    byte[] transform(byte[] currentData);
}
