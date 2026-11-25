import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 按 storage_file 快照备份托管媒体。复用项目的 MinIO SDK，无需桶列表权限。 */
public class MinioMediaBackup {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANIFEST_NAME = "manifest.json";
    // 沿用当前托管图片的大小上限。单次 PUT 可由 MinIO 原子检查同名对象。
    private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    record Manifest(int format, List<MediaObject> objects) {}

    record MediaObject(String id, String bucket, String objectKey, String contentType, long sizeBytes, String sha256) {
        String entryName() { return "objects/" + id; }
    }

    public static void main(String[] args) {
        try {
            if (args.length == 3 && "create".equals(args[0])) {
                Manifest manifest = JSON.readValue(Path.of(args[1]).toFile(), Manifest.class);
                try (MinioClient client = client()) {
                    create(client, requiredEnv("MINIO_BUCKET"), manifest, Path.of(args[2]));
                }
                System.out.println("Backup complete. Objects: " + manifest.objects().size());
            } else if (args.length == 2 && "verify".equals(args[0])) {
                try (ZipFile archive = new ZipFile(args[1])) {
                    System.out.println("Backup verified. Objects: " + verify(archive).objects().size());
                }
            } else if (args.length == 2 && "restore".equals(args[0])) {
                try (ZipFile archive = new ZipFile(args[1]); MinioClient client = client()) {
                    System.out.println("Restore verified. Objects: " + restore(client, requiredEnv("MINIO_BUCKET"), archive));
                }
            } else {
                throw new IllegalArgumentException("Usage: create <inventory.json> <new.zip> | verify <zip> | restore <zip>");
            }
        } catch (IllegalArgumentException | IOException failure) {
            // JSON 解析异常可能包含快照原文，只输出阶段及类型。
            if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                System.err.println("Invalid backup manifest");
            } else {
                System.err.println(failure.getMessage());
            }
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Media backup operation failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void create(MinioClient client, String bucket, Manifest manifest, Path archive) throws Exception {
        validate(manifest);
        requireBucket(manifest, bucket);
        Path target = archive.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Backup archive already exists");
        Path staged = Files.createTempFile(target.getParent(), ".media-backup-", ".partial");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(staged))) {
                for (MediaObject item : manifest.objects()) {
                    writeEntry(zip, item.entryName(), remoteBytes(client, item));
                }
                writeEntry(zip, MANIFEST_NAME, JSON.writeValueAsBytes(manifest));
            }
            // 完整 ZIP 关闭后才发布到指定文件名。重复执行时保留既有备份。
            Files.move(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    static Manifest verify(ZipFile archive) throws Exception {
        Set<String> entries = new HashSet<>();
        var members = archive.entries();
        while (members.hasMoreElements()) {
            if (!entries.add(members.nextElement().getName())) throw new IOException("Duplicate backup entry");
        }
        Manifest manifest;
        try (InputStream input = entry(archive, MANIFEST_NAME)) {
            manifest = JSON.readValue(input, Manifest.class);
        }
        validate(manifest);
        for (MediaObject item : manifest.objects()) {
            try (InputStream input = entry(archive, item.entryName())) {
                checkedBytes(input, item);
            }
        }
        if (entries.size() != manifest.objects().size() + 1) throw new IOException("Unexpected backup entries");
        return manifest;
    }

    static int restore(MinioClient client, String bucket, ZipFile archive) throws Exception {
        // 先校验整份备份，再开始写入。无需解压对象键到文件系统。
        Manifest manifest = verify(archive);
        requireBucket(manifest, bucket);
        for (MediaObject item : manifest.objects()) {
            try (InputStream input = entry(archive, item.entryName())) {
                byte[] data = checkedBytes(input, item);
                try {
                    client.putObject(PutObjectArgs.builder().bucket(bucket).object(item.objectKey())
                            .headers(Map.of("If-None-Match", "*")).contentType(item.contentType())
                            .stream(new ByteArrayInputStream(data), item.sizeBytes(), -1L).build());
                } catch (ErrorResponseException failure) {
                    if (failure.response().code() != 412) throw failure;
                    // 同名对象只在回读验证一致后算恢复成功，允许安全续跑。
                }
                remoteBytes(client, item);
            }
        }
        return manifest.objects().size();
    }

    private static byte[] remoteBytes(MinioClient client, MediaObject item) throws Exception {
        try (GetObjectResponse input = client.getObject(GetObjectArgs.builder().bucket(item.bucket())
                .object(item.objectKey()).build())) {
            if (!item.contentType().equalsIgnoreCase(input.headers().get("Content-Type"))) {
                throw new IOException("Content-Type mismatch for file " + item.id());
            }
            return checkedBytes(input, item);
        }
    }

    private static byte[] checkedBytes(InputStream input, MediaObject item) throws Exception {
        byte[] bytes = input.readNBytes((int) item.sizeBytes() + 1);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (bytes.length != item.sizeBytes() || !sha256.equals(item.sha256())) {
            throw new IOException("Size or SHA-256 mismatch for file " + item.id());
        }
        return bytes;
    }

    private static void validate(Manifest manifest) {
        if (manifest == null || manifest.format() != 1 || manifest.objects() == null) {
            throw new IllegalArgumentException("Invalid backup manifest");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (MediaObject item : manifest.objects()) {
            if (item == null || !matches(item.id(), "[0-9a-f]{32}") || !ids.add(item.id())
                    || !matches(item.bucket(), "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                    || !matches(item.objectKey(), "v1/(avatars|game-covers|ai-bot-avatars|ai-bot-portraits)/[A-Za-z0-9/_-]+\\.[a-z0-9]+")
                    || !names.add(item.bucket() + "/" + item.objectKey())
                    || !matches(item.contentType(), "image/[a-z0-9.+-]+")
                    || item.sizeBytes() <= 0 || item.sizeBytes() > MAX_IMAGE_BYTES
                    || !matches(item.sha256(), "[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid or duplicate media entry");
            }
        }
    }

    private static void requireBucket(Manifest manifest, String bucket) {
        if (manifest.objects().stream().anyMatch(item -> !bucket.equals(item.bucket()))) {
            throw new IllegalArgumentException("Selected MINIO_BUCKET does not match the backup manifest");
        }
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static InputStream entry(ZipFile archive, String name) throws IOException {
        ZipEntry member = archive.getEntry(name);
        if (member == null || member.isDirectory()) throw new IOException("Missing backup entry " + name);
        return archive.getInputStream(member);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static MinioClient client() {
        MinioClient client = MinioClient.builder().endpoint(requiredEnv("MINIO_ENDPOINT"))
                .credentials(requiredEnv("MINIO_ACCESS_KEY"), requiredEnv("MINIO_SECRET_KEY")).build();
        client.setTimeout(10_000, 30_000, 30_000);
        return client;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }
}
