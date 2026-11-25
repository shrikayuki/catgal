package com.catgal.server.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageConfigurationTest {
    @Test
    void enabledClientBuildsWithTheResolvedRuntimeDependencies() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setEnabled(true);
        properties.setAccessKey("test-access");
        properties.setSecretKey("test-secret-for-construction");
        try (var client = new StorageConfiguration().minioClient(properties)) {
            assertNotNull(client);
        }
    }

    @Test
    void enabledStorageRequiresItsOwnCredentials() {
        assertThrows(IllegalStateException.class,
                () -> new StorageConfiguration().minioClient(new StorageProperties()));
    }
}
