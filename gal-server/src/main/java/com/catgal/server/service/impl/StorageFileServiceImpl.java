/*
 * 阅读说明
 * 托管图片生命周期编排，元数据预留、对象上传、引用绑定和补偿清理分阶段执行。
 * purpose 选择业务引用策略，独立短事务避免在外部上传期间长时间占用数据库行锁。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.service.IFileBindingService;
import com.catgal.server.service.IFileBindingService.Reference;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.storage.AvatarImageValidator;
import com.catgal.server.storage.AiBotImageKind;
import com.catgal.server.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class StorageFileServiceImpl extends ServiceImpl<StorageFileMapper, StorageFile> implements IStorageFileService {
    private static final String UPLOADING = "UPLOADING";
    private static final String READY = "READY";
    private static final String CLEANUP_PENDING = "CLEANUP_PENDING";
    private static final String DELETED = "DELETED";
    private final IObjectStorageService objects;
    private final Map<String, IFileBindingService> bindings;
    private final CurrentUserProvider currentUser;
    private final StorageProperties properties;
    private final AvatarImageValidator images;
    private final TransactionTemplate transactions;

    public StorageFileServiceImpl(IObjectStorageService objects, CurrentUserProvider currentUser,
                                  StorageProperties properties, AvatarImageValidator images,
                                  PlatformTransactionManager transactionManager, List<IFileBindingService> bindingServices) {
        this.objects = objects;
        bindings = bindingServices.stream().collect(Collectors.toUnmodifiableMap(IFileBindingService::purpose, value -> value));
        this.currentUser = currentUser;
        this.properties = properties;
        this.images = images;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String updateAvatar(MultipartFile upload) {
        return uploadImage("AVATAR", currentUser.requireUserId(), null, upload).url();
    }

    @Override
    public UpdateResult updateGameCover(long gameId, Long revision, MultipartFile upload) {
        return uploadImage("GAME_COVER", gameId, revision, upload);
    }

    @Override
    public UpdateResult updateAiBotImage(long botId, AiBotImageKind kind, Long revision, MultipartFile upload) {
        return uploadImage(kind.purpose(), botId, revision, upload);
    }

    // 图片先完成内容校验，再预留元数据、上传对象和提交业务引用。
    // 失败时回查引用与 READY 状态，结果已提交则返回成功，否则进入持久化清理。
    private UpdateResult uploadImage(String purpose, long businessId, Long revision, MultipartFile upload) {
        Long owner = currentUser.requireUserId();
        requireEnabled();
        if (businessId <= 0) throw BusinessException.badRequest("业务ID必须为正整数");
        IFileBindingService binding = binding(purpose);
        AvatarImageValidator.Image image = images.validate(upload);
        StorageFile file = reserve(binding, businessId, owner, revision, image);
        boolean uploaded = false;
        try {
            objects.put(file.getBucket(), file.getObjectKey(), new ByteArrayInputStream(image.content()),
                    image.content().length, file.getContentType());
            uploaded = true;
            Long updatedRevision = completeImage(binding, file, revision);
            return new UpdateResult(file.getId(), binding.publicPath(file.getId()), updatedRevision);
        } catch (RuntimeException failure) {
            try {
                Reference current = binding.currentReference(businessId);
                StorageFile persisted = getById(file.getId());
                if (current != null && Objects.equals(file.getId(), current.fileId())
                        && persisted != null && READY.equals(persisted.getStatus())) {
                    return new UpdateResult(file.getId(), binding.publicPath(file.getId()), current.revision());
                }
                queueCleanup(file.getId(), uploaded ? now() : now().plusMinutes(1));
                if (uploaded) cleanup(file.getId());
            } catch (RuntimeException recoveryFailure) {
                if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
                log.warn("图片恢复任务待重试, fileId={}, errorType={}", file.getId(), recoveryFailure.getClass().getSimpleName());
            }
            log.warn("图片存储失败, fileId={}, errorType={}", file.getId(), failure.getClass().getSimpleName());
            if (failure instanceof BusinessException business && business.getHttpStatus() < 500) throw business;
            throw BusinessException.internalError("图片存储失败，请稍后重试", failure);
        }
    }

    // 独立短事务内锁业务引用、核对权限和 revision，生成服务端文件 ID 与分层对象键。
    private StorageFile reserve(IFileBindingService binding, long businessId, Long owner, Long revision,
                                AvatarImageValidator.Image image) {
        return transactions.execute(transaction -> {
            binding.authorizeUpload(businessId, owner, revision, binding.lockReference(businessId));
            LocalDateTime timestamp = now();
            String id = UUID.randomUUID().toString().replace("-", "");
            StorageFile file = new StorageFile();
            file.setId(id);
            file.setOwnerId(owner);
            file.setPurpose(binding.purpose());
            file.setBusinessId(businessId);
            file.setBucket(properties.getBucket());
            file.setObjectKey("v1/" + binding.objectPrefix() + "/" + timestamp.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    + "/" + businessId + "/" + id + "." + image.extension());
            file.setContentType(image.contentType());
            file.setSizeBytes((long) image.content().length);
            file.setSha256(sha256(image.content()));
            file.setStatus(UPLOADING);
            file.setCleanupAttempts(0);
            file.setNextAttemptAt(timestamp.plusHours(1));
            file.setCreateTime(timestamp);
            file.setUpdateTime(timestamp);
            if (!save(file)) throw BusinessException.internalError("创建文件记录失败");
            return file;
        });
    }

    // 对象上传成功后重新锁业务引用和文件行，核验版本并绑定当前文件。
    private Long completeImage(IFileBindingService binding, StorageFile file, Long revision) {
        return transactions.execute(transaction -> {
            // Every lifecycle operation locks its business source before the file metadata.
            Reference reference = binding.lockReference(sourceId(file));
            binding.authorizeUpload(sourceId(file), file.getOwnerId(), revision, reference);
            StorageFile current = baseMapper.selectForUpdate(file.getId());
            if (current == null || !UPLOADING.equals(current.getStatus())) {
                throw BusinessException.conflict("图片上传状态已变化，请重试");
            }
            current.setStatus(READY);
            current.setUpdateTime(now());
            requireUpdated(updateById(current));
            Long updatedRevision = binding.replaceReference(sourceId(file), file.getId(), revision);
            if (!Objects.equals(reference.fileId(), file.getId())) retirePrevious(binding, sourceId(file), reference.fileId());
            return updatedRevision;
        });
    }

    @Override
    public UpdateResult clearGameCover(long gameId, Long revision) {
        return clearImage("GAME_COVER", gameId, revision);
    }

    @Override
    public UpdateResult clearAiBotImage(long botId, AiBotImageKind kind, Long revision) {
        return clearImage(kind.purpose(), botId, revision);
    }

    // 解除业务引用并安排旧文件回收，文件服务与不同业务归属通过 binding 接口解耦。
    private UpdateResult clearImage(String purpose, long businessId, Long revision) {
        long owner = currentUser.requireUserId();
        requireEnabled();
        IFileBindingService binding = binding(purpose);
        return transactions.execute(transaction -> {
            Reference reference = binding.lockReference(businessId);
            binding.authorizeUpload(businessId, owner, revision, reference);
            Long updatedRevision = binding.replaceReference(businessId, null, revision);
            retirePrevious(binding, businessId, reference.fileId());
            return new UpdateResult(null, null, updatedRevision);
        });
    }

    private void retirePrevious(IFileBindingService binding, long businessId, String id) {
        if (id == null) return;
        StorageFile old = baseMapper.selectForUpdate(id);
        if (old != null && binding.purpose().equals(old.getPurpose()) && sourceId(old) == businessId) {
            markCleanup(old, now());
        }
    }

    private void queueCleanup(String id, LocalDateTime when) {
        StorageFile snapshot = getById(id);
        if (snapshot == null) return;
        transactions.executeWithoutResult(transaction -> {
            Reference reference = binding(snapshot.getPurpose()).lockReference(sourceId(snapshot));
            StorageFile current = baseMapper.selectForUpdate(id);
            if (current == null || !snapshot.getPurpose().equals(current.getPurpose()) || sourceId(snapshot) != sourceId(current)
                    || DELETED.equals(current.getStatus()) || isReferenced(reference, id)) return;
            markCleanup(current, when);
        });
    }

    private void markCleanup(StorageFile file, LocalDateTime when) {
        file.setStatus(CLEANUP_PENDING);
        file.setNextAttemptAt(when);
        file.setUpdateTime(now());
        requireUpdated(updateById(file));
    }

    @Override
    // 先在短事务领取清理轮次，再删除外部对象，最终按轮次条件标记 DELETED。
    // 中途失败保留清理状态供后续任务重试，对象删除操作须具备幂等性。
    public void cleanup(String id) {
        if (!properties.isEnabled()) return;
        StorageFile snapshot = getById(id);
        if (snapshot == null) return;
        StorageFile claimed = transactions.execute(transaction -> {
            Reference reference = binding(snapshot.getPurpose()).lockReference(sourceId(snapshot));
            StorageFile file = baseMapper.selectForUpdate(id);
            if (file == null || !snapshot.getPurpose().equals(file.getPurpose()) || sourceId(snapshot) != sourceId(file)
                    || DELETED.equals(file.getStatus()) || isReferenced(reference, id)
                    || file.getNextAttemptAt().isAfter(now())) return null;
            file.setStatus(CLEANUP_PENDING);
            file.setCleanupAttempts(file.getCleanupAttempts() + 1);
            file.setNextAttemptAt(now().plusMinutes(2));
            file.setUpdateTime(now());
            requireUpdated(updateById(file));
            return file;
        });
        if (claimed == null) return;
        try {
            objects.delete(claimed.getBucket(), claimed.getObjectKey());
            // A failed final database write leaves an idempotent cleanup task for the next worker.
            requireUpdated(update(new LambdaUpdateWrapper<StorageFile>().eq(StorageFile::getId, id)
                    .eq(StorageFile::getStatus, CLEANUP_PENDING)
                    .eq(StorageFile::getCleanupAttempts, claimed.getCleanupAttempts())
                    .set(StorageFile::getStatus, DELETED).set(StorageFile::getUpdateTime, now())));
        } catch (RuntimeException failure) {
            log.warn("文件清理待重试, fileId={}, errorType={}", id, failure.getClass().getSimpleName());
        }
    }

    @Override
    public void cleanupPending() {
        if (!properties.isEnabled()) return;
        for (StorageFile file : baseMapper.selectCleanupBatch(20)) {
            try {
                cleanup(file.getId());
            } catch (RuntimeException failure) {
                log.warn("文件清理任务失败, fileId={}, errorType={}", file.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public Download openAvatar(String id) {
        return openImage("AVATAR", id);
    }

    @Override
    public Download openGameCover(String id) {
        return openImage("GAME_COVER", id);
    }

    @Override
    public Download openAiBotImage(AiBotImageKind kind, String id) {
        return openImage(kind.purpose(), id);
    }

    // 文件 ID 经格式和业务可见性校验后才打开对象流，物理 bucket/key 由元数据解析。
    private Download openImage(String purpose, String id) {
        requireEnabled();
        if (id == null || !id.matches("[0-9a-f]{32}")) throw BusinessException.notFound("图片不存在");
        StorageFile file = binding(purpose).visibleFile(id);
        if (file == null) throw BusinessException.notFound("图片不存在");
        try {
            return new Download(objects.open(file.getBucket(), file.getObjectKey()), file.getSizeBytes(), file.getContentType());
        } catch (RuntimeException failure) {
            log.warn("图片读取失败, fileId={}, errorType={}", id, failure.getClass().getSimpleName());
            throw BusinessException.internalError("图片暂时无法读取");
        }
    }

    private static boolean isReferenced(Reference reference, String id) {
        return reference != null && Objects.equals(reference.fileId(), id);
    }

    // 按 purpose 选择业务引用策略，头像、游戏封面和机器人图片共用上传清理编排。
    private IFileBindingService binding(String purpose) {
        IFileBindingService binding = bindings.get(purpose);
        if (binding == null) throw new IllegalStateException("Unsupported file purpose");
        return binding;
    }

    private static long sourceId(StorageFile file) {
        if (file.getBusinessId() != null) return file.getBusinessId();
        if ("AVATAR".equals(file.getPurpose())) return file.getOwnerId();
        throw new IllegalStateException("File business source is missing");
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw BusinessException.internalError("文件存储功能尚未启用");
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) throw BusinessException.internalError("文件状态保存失败");
    }

    private LocalDateTime now() { return baseMapper.currentTime(); }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
