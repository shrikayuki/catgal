package com.catgal.server.storage;

import com.catgal.common.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarImageValidatorTest {
    private final AvatarImageValidator validator = new AvatarImageValidator();

    static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIOHelper.write(width, height, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void realPngAndUppercaseExtensionAreAccepted() throws Exception {
        var result = validator.validate(new MockMultipartFile("file", "Avatar.PNG", "image/png", png(2, 2)));
        assertEquals("png", result.extension());
        assertEquals("image/png", result.contentType());
        assertArrayEquals(png(2, 2), result.content());
    }

    @Test
    void jpegAndGifRetainTheirSupportedFormats() throws Exception {
        for (String format : new String[]{"jpeg", "gif"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIOHelper.write(2, 2, format, output);
            var result = validator.validate(new MockMultipartFile("file", "avatar." + format,
                    "image/" + format, output.toByteArray()));
            assertEquals("image/" + format, result.contentType());
        }
    }

    @Test
    void webpIsDecodedThroughTheRegisteredPlugin() {
        byte[] webp = java.util.Base64.getDecoder().decode(
                "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA==");
        var result = validator.validate(new MockMultipartFile("file", "avatar.webp", "image/webp", webp));
        assertEquals("webp", result.extension());
        assertEquals("image/webp", result.contentType());
    }

    @Test
    void fakeAndTruncatedImagesAreRejected() throws Exception {
        for (byte[] body : new byte[][]{"<script>fake image</script>".getBytes(),
                java.util.Arrays.copyOf(png(2, 2), 12)}) {
            assertEquals(400, assertThrows(BusinessException.class, () -> validator.validate(
                    new MockMultipartFile("file", "avatar.png", "image/png", body))).getHttpStatus());
        }
    }

    @Test
    void decodedFormatMustMatchExtensionAndMime() throws Exception {
        byte[] body = png(2, 2);
        assertThrows(BusinessException.class, () -> validator.validate(
                new MockMultipartFile("file", "avatar.jpg", "image/jpeg", body)));
        assertThrows(BusinessException.class, () -> validator.validate(
                new MockMultipartFile("file", "avatar.png", "image/gif", body)));
    }

    @Test
    void dimensionsAreCheckedBeforePixelDecoding() throws Exception {
        assertThrows(BusinessException.class, () -> validator.validate(
                new MockMultipartFile("file", "avatar.png", "image/png", png(4097, 1))));
    }

    @Test
    void actualBytesAreLimitedEvenIfMetadataUnderstatesTheSize() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("avatar.png");
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(1L);
        when(file.getBytes()).thenReturn(new byte[AvatarImageValidator.MAX_BYTES + 1]);
        assertThrows(BusinessException.class, () -> validator.validate(file));
        when(file.getBytes()).thenReturn(new byte[0]);
        assertThrows(BusinessException.class, () -> validator.validate(file));
    }

    @Test
    void missingUnsupportedEmptyAndOverLimitInputsAreRejected() {
        assertThrows(BusinessException.class, () -> validator.validate(null));
        for (MultipartFile file : new MultipartFile[]{new MockMultipartFile("file", new byte[0]),
                new MockMultipartFile("file", "script.exe", "image/png", new byte[]{1}),
                new MockMultipartFile("file", "avatar", "image/png", new byte[]{1}),
                new MockMultipartFile("file", "avatar.png", "image/png", new byte[AvatarImageValidator.MAX_BYTES + 1])}) {
            assertThrows(BusinessException.class, () -> validator.validate(file));
        }
    }

    @Test
    void fileReadFailuresUseTheBusinessErrorContract() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("avatar.png");
        when(file.getContentType()).thenReturn("image/png");
        when(file.getBytes()).thenThrow(new IOException("fixture read failure"));
        assertEquals(500, assertThrows(BusinessException.class, () -> validator.validate(file)).getHttpStatus());
    }

    @Test
    void everyGifFrameHasItsOwnDimensionCheck() throws Exception {
        byte[] gif = animatedGif(2, 4097);
        assertThrows(BusinessException.class, () -> validator.validate(
                new MockMultipartFile("file", "avatar.gif", "image/gif", gif)));
        assertEquals("gif", validator.validate(new MockMultipartFile("file", "avatar.gif", "image/gif",
                animatedGif(2, 1))).extension());
    }

    @Test
    void animationsHaveABoundedFrameCount() throws Exception {
        byte[] gif = animatedGif(101, 1);
        assertThrows(BusinessException.class, () -> validator.validate(
                new MockMultipartFile("file", "avatar.gif", "image/gif", gif)));
    }

    @Test
    void decoderBackpressureIsExplicitAndReleasesCapacity() throws Exception {
        var slots = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(validator, "decoders");
        assertNotNull(slots);
        slots.acquire(2);
        try {
            assertEquals(429, assertThrows(BusinessException.class, () -> validator.validate(null)).getHttpStatus());
        } finally {
            slots.release(2);
        }
        assertEquals("png", validator.validate(new MockMultipartFile("file", "avatar.png", "image/png", png(1, 1))).extension());
    }

    private static byte[] animatedGif(int frames, int secondWidth) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int frame = 0; frame < frames; frame++) {
                BufferedImage image = new BufferedImage(frame == 1 ? secondWidth : 1, 1, BufferedImage.TYPE_INT_RGB);
                writer.writeToSequence(new javax.imageio.IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static class ImageIOHelper {
        static void write(int width, int height, String format, ByteArrayOutputStream output) throws IOException {
            assertTrue(javax.imageio.ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output));
        }
    }
}
