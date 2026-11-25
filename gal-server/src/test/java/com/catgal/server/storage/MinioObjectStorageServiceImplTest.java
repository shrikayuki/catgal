package com.catgal.server.storage;

import com.catgal.server.service.impl.MinioObjectStorageServiceImpl;
import io.minio.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioObjectStorageServiceImplTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MinioClient> clients = mock(ObjectProvider.class);
    private final MinioClient client = mock(MinioClient.class);
    private final MinioObjectStorageServiceImpl storage = new MinioObjectStorageServiceImpl(clients);

    @Test
    void successfulUploadUsesTheManagedBucketKeyAndMime() throws Exception {
        when(clients.getIfAvailable()).thenReturn(client);
        storage.put("catgal-media", "v1/avatars/fixture.png", new java.io.ByteArrayInputStream(new byte[]{1, 2}), 2, "image/png");
        ArgumentCaptor<PutObjectArgs> arguments = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(arguments.capture());
        assertEquals("catgal-media", arguments.getValue().bucket());
        assertEquals("v1/avatars/fixture.png", arguments.getValue().object());
    }

    @Test
    void sdkFailureCannotBeReportedAsSuccess() throws Exception {
        when(clients.getIfAvailable()).thenReturn(client);
        RuntimeException original = new IllegalStateException("storage rejected request");
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(original);
        assertSame(original, assertThrows(ObjectStorageException.class,
                () -> storage.put("catgal-media", "v1/avatars/new.png", new java.io.ByteArrayInputStream(new byte[]{1}), 1, "image/png")).getCause());
    }

    @Test
    void streamCloseFailurePreservesTheOriginalUploadFailure() throws Exception {
        when(clients.getIfAvailable()).thenReturn(client);
        RuntimeException original = new IllegalStateException("upload unavailable");
        java.io.IOException closing = new java.io.IOException("stream close unavailable");
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(original);
        var input = new java.io.ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() throws java.io.IOException { throw closing; }
        };
        assertSame(original, assertThrows(ObjectStorageException.class,
                () -> storage.put("catgal-media", "v1/avatars/new.png", input, 1, "image/png")).getCause());
        assertArrayEquals(new Throwable[]{closing}, original.getSuppressed());
    }

    @Test
    void downloadStreamIsReturnedToTheHttpLifecycle() throws Exception {
        when(clients.getIfAvailable()).thenReturn(client);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);
        assertSame(response, storage.open("catgal-media", "v1/avatars/new.png"));
        verify(response, never()).close();
    }

    @Test
    void deletionTargetsOnlyTheSuppliedObject() throws Exception {
        when(clients.getIfAvailable()).thenReturn(client);
        storage.delete("catgal-media", "v1/avatars/new.png");
        ArgumentCaptor<RemoveObjectArgs> arguments = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(arguments.capture());
        assertEquals("catgal-media", arguments.getValue().bucket());
        assertEquals("v1/avatars/new.png", arguments.getValue().object());
        verify(client, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void disabledAndFailedReadOrDeleteOperationsPropagate() throws Exception {
        assertThrows(ObjectStorageException.class, () -> storage.open("catgal-media", "missing"));
        when(clients.getIfAvailable()).thenReturn(client);
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IllegalStateException("read failed"));
        doThrow(new IllegalStateException("delete failed")).when(client).removeObject(any(RemoveObjectArgs.class));
        assertThrows(ObjectStorageException.class, () -> storage.open("catgal-media", "missing"));
        assertThrows(ObjectStorageException.class, () -> storage.delete("catgal-media", "missing"));
    }
}
